# native/ — reproducible perl + ExifTool build

This directory contains everything needed to rebuild the perl interpreter and
the ExifTool script this app ships, from pinned upstream source, with
byte-for-byte reproducibility. Adapted from
[bestvibes/exiftoolwrapper-android](https://github.com/bestvibes/exiftoolwrapper-android)
(MIT License) — see [`../native/NOTICE`](./NOTICE) for the full attribution
and what changed.

## Why this exists

RAW photo formats — Olympus ORF included — are read-only in AndroidX
`ExifInterface`; `saveAttributes()` only supports JPEG/PNG/WebP (confirmed
against the library source, not assumed). ExifTool is the one tool with
genuine, mature ORF write support, but it's a Perl script — Android doesn't
ship Perl — so this directory cross-compiles a minimal perl interpreter and
bundles it with ExifTool as native `jniLibs`, invoked via `ProcessBuilder`
with a fixed, hardcoded argv (see
`app/src/main/java/com/olyphototagger/app/exiftool/`). There's no free-text
user input anywhere near the exiftool invocation — the app builds one fixed
GPS-write command itself — so the general command-sanitization layer the
upstream project needs (it exposes a free-form exiftool command UI) wasn't
carried over.

## Layout

```
native/
  PINS              # version + integrity-hash pins (sourced by build.sh and CI)
  NOTICE            # upstream attribution (MIT)
  build.sh          # cross-builds perl per-ABI; bundles ExifTool into perl5.tar
  docker-build.sh   # local-dev wrapper for macOS (works around APFS case-collision)
  perl-cross/       # git submodule, pinned to PERL_CROSS_TAG in PINS
  patches/          # local patches over upstream perl (Bionic quirks)
  src/              # downloaded source tarballs (gitignored)
  build/            # per-ABI build trees (gitignored)
  out/              # build outputs (gitignored; CI copies these into the app tree)
```

## Outputs

`build.sh` produces, per ABI:

- `out/<abi>/libperl.so` — the perl interpreter, stripped, renamed so
  Android's installer places it under `applicationInfo.nativeLibraryDir`.
- `out/<abi>/jniLibs/libperl_xs_*.so` — one shared object per XS module
  (`POSIX`, `List::Util`, etc.), renamed from `auto/<dist>/<dist>.so` so
  Android's installer extracts them via the standard `jniLibs` mechanism.
- `out/<abi>/xs_manifest.txt` — maps each XS module's canonical archlib path
  back to its `libperl_xs_*.so` filename; `AssetExtractor` (Kotlin) uses this
  on-device to symlink `filesDir/perl5/arch/auto/.../*.so` ->
  `nativeLibraryDir/libperl_xs_*.so` so perl's `DynaLoader` finds them.

And once, architecture-independent:

- `out/assets/perl5.tar` — the ExifTool script + its `lib/Image/ExifTool/`
  tree + perl's `@INC` (`Carp.pm`, `strict.pm`, etc.), shipped under
  `app/src/main/assets/` and extracted on first launch.

## Running the build

This app's CI (`.github/workflows/native.yml`) runs `native/build.sh`
directly on an `ubuntu-latest` runner — no Docker needed there, since the
GNU toolchain it expects is already present.

Locally:

- **Linux**: `native/build.sh all` (needs the NDK version in `PINS`; set
  `ANDROID_NDK_HOME` if it's not at the default location).
- **macOS**: `native/docker-build.sh all` — perl ships both `lib/Pod/` (the
  namespace) and `lib/pod/` (pod2man helpers), which collide on
  case-insensitive APFS during a naive `cp -R`; the Docker wrapper sidesteps
  this by keeping the scratch tree inside the container.

```sh
git submodule update --init native/perl-cross
native/build.sh arm64-v8a       # one ABI
native/build.sh all             # all four ABIs + assets
```

A successful run prints the SHA256 of each output.

## Bumping versions

Edit `PINS`, set the new version and the new integrity hash *fetched live
from the upstream source*, not copied from another project — see the git
history of this file for a worked example (the initial values ported from
upstream were themselves stale/wrong: an unenforced and incorrectly-typed
NDK hash, and an ExifTool version whose checksum page had already been
rotated out).
