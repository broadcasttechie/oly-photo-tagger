#!/usr/bin/env python3
"""Verifies a GPS EXIF write touched nothing but GPS tags (plus the offset pointers a
correct writer must update when new tag data shifts everything after it).

Takes a directory of <name>.before / <name>.after file pairs — produced on-device by
GpsWriteIntegrityInstrumentedTest, then `adb pull`ed — and for each pair:

  1. Diffs the full exiftool tag set. Anything beyond GPS tags and known offset pointers
     (ThumbnailOffset, PreviewImageStart, StripOffsets) is flagged.
  2. Hashes embedded ThumbnailImage/PreviewImage/JpgFromRaw bytes before vs after.
  3. For JPEGs: hashes the compressed scan data (from the first SOS marker to EOF) —
     untouched by any EXIF edit, so this is the definitive "was the picture itself
     altered" check.
  4. For files with StripOffsets/StripByteCounts (TIFF-based RAW, e.g. Olympus ORF):
     extracts and hashes the actual sensor strip bytes at that exact offset/length —
     the gold-standard check for RAW files, since PreviewImage is a different, smaller
     embedded image, not the actual sensor readout.
  5. Runs exiftool -validate on both files.

Requires a local `exiftool` (any reasonably recent version — this only reads metadata,
it doesn't need to match the version bundled in the app).

Usage:
    python3 tools/verify_gps_write_integrity.py <dir-of-before-after-pairs>
"""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ALLOWED_CHANGED_PREFIXES = ("GPS", "Composite:GPS")
ALLOWED_CHANGED_EXACT = {
    "IFD1:ThumbnailOffset",
    "Olympus:PreviewImageStart",
    "IFD0:StripOffsets",
    "SourceFile",
    "System:FileName",
    "System:FileModifyDate",
    "System:FileAccessDate",
    "System:FileInodeChangeDate",
    "System:FileSize",
    "System:FilePermissions",
    "File:FileSize",
    "File:FileModifyDate",
    "File:FileAccessDate",
    "File:FileInodeChangeDate",
    "File:FilePermissions",
}


def run(*args: str) -> str:
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout


def exiftool_json(path: Path) -> dict:
    return json.loads(run("exiftool", "-j", "-a", "-u", "-G1", "-s", str(path)))[0]


def hash_binary_tag(path: Path, tag: str) -> str | None:
    result = subprocess.run(
        ["exiftool", "-b", f"-{tag}", str(path)], capture_output=True, check=False
    )
    return hashlib.sha256(result.stdout).hexdigest() if result.stdout else None


def find_jpeg_sos(data: bytes) -> int:
    i = 2  # skip SOI (FFD8)
    while i < len(data) - 1:
        if data[i] != 0xFF:
            raise ValueError(f"Expected marker at {i}, got {data[i]:02x}")
        marker = data[i + 1]
        if marker == 0xDA:  # SOS: header ends, entropy-coded scan data begins
            seg_len = (data[i + 2] << 8) | data[i + 3]
            return i + 2 + seg_len
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2  # markers with no length field
            continue
        seg_len = (data[i + 2] << 8) | data[i + 3]
        i += 2 + seg_len
    raise ValueError("SOS marker not found")


def hash_jpeg_scan_data(path: Path) -> tuple[str, int]:
    data = path.read_bytes()
    start = find_jpeg_sos(data)
    scan = data[start:]
    return hashlib.sha256(scan).hexdigest(), len(scan)


def strip_data_hash(path: Path) -> tuple[str, int, int] | None:
    """Returns (hash, offset, length) for TIFF StripOffsets/StripByteCounts, or None."""
    result = subprocess.run(
        ["exiftool", "-StripOffsets", "-StripByteCounts", "-s3", str(path)],
        capture_output=True, text=True, check=False,
    )
    lines = result.stdout.strip().split("\n")
    if len(lines) != 2 or not all(lines):
        return None
    offset, length = int(lines[0]), int(lines[1])
    data = path.read_bytes()
    chunk = data[offset:offset + length]
    return hashlib.sha256(chunk).hexdigest(), offset, length


def validate(path: Path) -> str:
    return run("exiftool", "-validate", "-warning", "-error", "-s3", str(path)).strip()


def verify_pair(before: Path, after: Path) -> bool:
    ok = True
    before_json = exiftool_json(before)
    after_json = exiftool_json(after)
    all_keys = set(before_json) | set(after_json)

    added = sorted(set(after_json) - set(before_json))
    removed = sorted(set(before_json) - set(after_json))
    unexpected_added = [k for k in added if not any(k.startswith(p) for p in ALLOWED_CHANGED_PREFIXES)]
    unexpected_removed = [k for k in removed if not any(k.startswith(p) for p in ALLOWED_CHANGED_PREFIXES)]

    unexpected_changes = []
    for k in sorted(all_keys):
        if k in before_json and k in after_json and before_json[k] != after_json[k]:
            if k in ALLOWED_CHANGED_EXACT or any(k.startswith(p) for p in ALLOWED_CHANGED_PREFIXES):
                continue
            unexpected_changes.append((k, before_json[k], after_json[k]))

    print(f"  Tags added (expected GPS*): {added}")
    if unexpected_removed:
        print(f"  *** UNEXPECTED REMOVED TAGS: {unexpected_removed}")
        ok = False
    if unexpected_added:
        print(f"  *** UNEXPECTED ADDED (non-GPS) TAGS: {unexpected_added}")
        ok = False
    if unexpected_changes:
        print("  *** UNEXPECTED VALUE CHANGES:")
        for k, bv, av in unexpected_changes:
            print(f"      {k}: {str(bv)[:80]!r} -> {str(av)[:80]!r}")
        ok = False
    else:
        print("  Value changes: only GPS tags + expected offset pointers.")

    for tag in ("ThumbnailImage", "PreviewImage", "JpgFromRaw", "OtherImage"):
        bh, ah = hash_binary_tag(before, tag), hash_binary_tag(after, tag)
        if bh is None and ah is None:
            continue
        status = "MATCH" if bh == ah else "*** MISMATCH ***"
        print(f"  {tag}: [{status}]")
        ok &= bh == ah

    if before.suffix.upper() in (".JPG", ".JPEG"):
        bh, blen = hash_jpeg_scan_data(before)
        ah, alen = hash_jpeg_scan_data(after)
        status = "MATCH" if (bh, blen) == (ah, alen) else "*** MISMATCH ***"
        print(f"  JPEG scan data ({blen} bytes): [{status}]")
        ok &= (bh, blen) == (ah, alen)

    b_strip = strip_data_hash(before)
    a_strip = strip_data_hash(after)
    if b_strip and a_strip:
        (bh, boff, blen), (ah, aoff, alen) = b_strip, a_strip
        status = "MATCH" if (bh, blen) == (ah, alen) else "*** MISMATCH ***"
        print(f"  RAW strip data (offset {boff}->{aoff}, {blen} bytes): [{status}]")
        ok &= (bh, blen) == (ah, alen)

    bsize, asize = before.stat().st_size, after.stat().st_size
    print(f"  File size: before={bsize} after={asize} delta={asize - bsize}")
    print(f"  Validate before: {validate(before)}")
    print(f"  Validate after:  {validate(after)}")
    return ok


def main():
    directory = Path(sys.argv[1])
    stems = sorted({p.stem for p in directory.glob("*.before")})
    if not stems:
        print(f"No *.before files found in {directory}", file=sys.stderr)
        sys.exit(2)

    overall_ok = True
    for stem in stems:
        print(f"\n{'=' * 70}\n{stem}\n{'=' * 70}")
        overall_ok &= verify_pair(directory / f"{stem}.before", directory / f"{stem}.after")

    print(f"\n{'=' * 70}")
    print("OVERALL:", "ALL CLEAN" if overall_ok else "*** ISSUES FOUND — see above ***")
    sys.exit(0 if overall_ok else 1)


if __name__ == "__main__":
    main()
