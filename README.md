# Oly Photo Tagger

Android app for reliably geotagging photos from an Olympus OM-D E-M5 Mark III, replacing
the crash-prone OI.Share geotag workflow.

## How it works

The camera mounts over USB as plain Mass Storage. The app:

1. Uses the Storage Access Framework to access the mounted camera card.
2. Enumerates `DCIM/` for JPEG + `.ORF` files, treating RAW+JPEG pairs as one unit.
3. Reads each photo's `DateTimeOriginal` EXIF timestamp.
4. Fetches the matching GPS track from whichever GPS source is active — a self-hosted
   [Dawarich](https://dawarich.app) instance, or GPX files imported via a file picker or
   share-intent (multiple imports pool together into one merged track).
5. Interpolates lat/lon between bracketing track points, honoring a configurable max
   time-gap threshold (skip and flag rather than interpolate across large gaps).
6. Writes GPS EXIF tags in place via a safe temp-file → verify → rename sequence.

## Safeguards

- Dry-run preview with explicit confirmation before any write
- Skip files that already have GPS EXIF (overwrite requires explicit confirm)
- RAW+JPEG pairs always tagged together with identical data
- Persistent change log of every write for auditing
- Interrupted writes leave either the untouched original or an orphaned `.tmp` — never a
  corrupted file

## Known limitations

See [`docs/known-limitations.md`](docs/known-limitations.md) — currently just one:
OM Image Share doesn't show RAW files this app tags as geotagged (JPEG is unaffected,
and the RAW's GPS data itself is complete and correct everywhere else). Root cause
confirmed; not fixed, by deliberate choice, since a real fix trades away exiftool's
maturity for hand-rolled binary format surgery over a cosmetic-only gap.

## Stack

Kotlin, Jetpack Compose, Material 3, Ktor client (Dawarich API), AndroidX ExifInterface,
Room (change log, imported GPX tracks), DataStore (settings). minSdk 26, target/compile
SDK 35.

## Building

Open in Android Studio, or `./gradlew assembleDebug` with a JDK 17+ and the Android SDK
installed.

## License

[GPL-3.0-or-later](LICENSE). The app is intended for distribution via
[F-Droid](https://f-droid.org); all dependencies are FOSS and no proprietary services are
used.
