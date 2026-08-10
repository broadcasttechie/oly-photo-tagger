# Known limitations

## OM Image Share / OI.Share doesn't show RAW files as geotagged

**Status:** confirmed root cause, not fixed, low priority — cosmetic only, no data is
missing or wrong.

**Symptom:** after this app geotags a photo pair, OM Image Share (OI.Share) correctly
shows the **JPEG** as geotagged (satellite icon), but shows the **RAW (`.ORF`)** as *not*
geotagged — even though the RAW file's GPS EXIF is complete and correct. Every other
reader (Google Photos, `exiftool`, this app's own verification read) sees the RAW's GPS
data fine. On camera playback, all photos show as tagged, but this is very likely because
playback renders from the JPEG/embedded preview rather than actually decoding the RAW.

### Investigation (2026-08-10)

Confirmed the actual GPS data is not the problem, via `SamplePhotos/full_examples/` (a
real untagged / OI.Share-tagged / app-tagged three-way set of the same photos) and a
live SD card with one photo tagged by each side:

- **Tag names match exactly.** A full untargeted `exiftool` diff (every tag, not just
  `GPS*`) between untagged and OI.Share-tagged shows *only* the standard GPS tags added
  (`GPSLatitude(Ref)`, `GPSLongitude(Ref)`, `GPSAltitude(Ref)`, `GPSStatus`) — nothing
  else in the file changes. This app's write adds the identical tag set.
- **Byte-level GPS IFD encoding matches exactly.** `exiftool -v3` structural dumps show
  the same 8 directory entries, same tag IDs, same types (`rational64u[3]` for lat/long,
  etc.), same order, little-endian in both — the only differences are the actual
  coordinate *values*, as expected for different shots.
- **Nothing else on the SD card changes when OI.Share tags a photo.** Checked every
  other file/folder (`ALBM/ALB87ZZ9.BIN`, `GPSLOG/`) — `ALBM`'s modification time
  predates both taggings by ~3 hours, ruling out any external per-shot tagging index.
- **The real difference: where the GPS data physically lives in the file.** A full
  byte-level diff (`cmp -l`) between an untagged ORF and OI.Share's own tagged version of
  the *same file* shows only 53 bytes differ, all within a ~174-byte span starting at
  file offset `0x2d2`. That's a placeholder GPS IFD the camera pre-reserves at capture
  time (zeroed, declaring just 1 entry — `GPSVersionID`) specifically so a companion app
  can fill it in later *without resizing the file*. OI.Share writes into that exact spot;
  the `GPSInfo` pointer at offset `0x011a` (in `IFD0`) is untouched — still `0x2d2` before
  and after.

  This app's write, by contrast, changes that pointer to `0x145564` — over a megabyte
  into the file, near the actual image data. `exiftool` doesn't know about the camera's
  reserved placeholder block (there's no way for a generic TIFF parser to know unlabeled
  zeroed space is "reserved," as opposed to just empty), so growing the GPS IFD from 1 to
  8 entries makes it write the new, larger IFD to fresh space elsewhere and repoint to it
  — which is fully valid, standards-compliant EXIF, just not where the camera expected it.
  This is also why this app's RAW writes come out ~150-330KB *smaller* than the original:
  confirmed via `exiftool`'s own FAQ (https://exiftool.sourceforge.net/faq.html, Q13a)
  that it deliberately does not preserve camera-reserved unused blocks when rewriting.

- **This isn't a flag we're missing.** Tried `-overwrite_original_in_place`, exiftool's
  option most likely to matter here — it changes nothing about internal layout (only
  where the *final* file lands, not how it's built), and the GPS IFD still relocated.
  exiftool's own FAQ describes discarding these padding blocks as deliberate, general
  behavior, with no documented option to reuse them (unlike a *different*, unrelated
  padding case in XMP writes, which does have one).

**Conclusion:** OM Image Share's RAW-file "geotagged" indicator most likely checks the
GPS placeholder at the camera's fixed, known offset directly, rather than genuinely
resolving the `GPSInfo` pointer the way a standards-compliant parser (including its own
JPEG-reading code path, apparently) would. Third-party RAW writes that don't happen to
land in that exact spot are invisible to it, regardless of correctness.

### How this could be fixed, if it's ever worth revisiting

Reproduce the camera's own layout: before invoking `exiftool`, pre-expand the existing
placeholder GPS IFD's declared entry count from 1 to 8 (with zero-valued entries for the
7 tags not yet present), staying within the camera's pre-reserved ~174-byte block. With
all 8 tags already "existing" (just zero-valued), `exiftool`'s in-place *value* update
path — the same path it uses for changing an existing tag's value without adding/removing
entries — should be able to fill them in without growing or relocating the IFD, keeping
the `GPSInfo` pointer unchanged and matching OI.Share's layout exactly.

This was investigated but deliberately not implemented, because it means abandoning
exiftool's mature, heavily-tested TIFF rewriting for hand-rolled binary IFD construction
against an undocumented, reverse-engineered convention specific to this one camera's
firmware — not verified to hold across other Olympus models or firmware versions, and a
meaningfully different (higher) risk profile than anything else in this app's write path,
for a fix whose only benefit is a checkmark icon in one companion app. Given this app's
core priority is photo safety over cosmetic parity, that trade wasn't taken. If revisited:
build and validate it as an isolated, heavily-tested pre-processing step (never touching
the actual GPS values or triggering `exiftool` until the placeholder is confirmed safely
expanded), and treat any camera model/firmware where the reserved block doesn't match the
expected size or structure as unsupported by the optimization (falling back to today's
behavior, not failing the write).

### Reproducing this investigation

Real (unpublished — see `SamplePhotos/.gitignore` handling) sample data used:
`SamplePhotos/full_examples/{01_untagged,02_tagged_by_oishare,03_tagged_by_olyphototagger}/`.
Useful commands:

```bash
# Full tag diff, not just GPS
exiftool -j -a -u -G1 -s before.orf > before.json
exiftool -j -a -u -G1 -s after.orf > after.json

# GPSInfo pointer target + GPS IFD structure
exiftool -v3 file.orf | grep "Tag 0x8825" -A1
exiftool -v3 file.orf | grep -A20 "GPS directory"

# Complete byte-level diff (only meaningful when file sizes match)
cmp -l before.orf after.orf
```
