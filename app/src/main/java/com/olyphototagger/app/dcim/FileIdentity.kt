package com.olyphototagger.app.dcim

/**
 * Cache key for a CameraFile, cheap enough to compute from metadata DcimScanner already
 * gathers during the DCIM directory listing — no extra file I/O needed to compute it.
 *
 * Folder + filename alone isn't safe: cameras reuse filenames after a card format or a
 * sequence-counter wraparound, so two genuinely different photos can land on the same
 * name over the life of a card. Adding size + last-modified essentially never collides
 * for two different exposures — and both are already read off the SAF directory listing,
 * so this costs nothing extra to compute.
 *
 * Deliberately NOT the SAF content:// URI, despite also being "free": document IDs aren't
 * guaranteed stable across remount sessions for every DocumentsProvider — re-plugging the
 * camera, a different USB port, or the OS re-enumerating the tree can change them even
 * when the underlying file hasn't. A poor durable cache key despite the low cost.
 *
 * Deliberately NOT a content hash: correct, but requires reading the whole file — for RAW
 * files especially, that's the exact cost this cache exists to avoid paying twice.
 *
 * A side effect worth relying on: writing GPS into a file changes its size and/or
 * mtime, so a freshly-written file naturally gets a new key next scan rather than
 * matching a stale cache entry from before the write.
 */
fun CameraFile.identityKey(): String =
    "$folderName/$displayName|$sizeBytes|${lastModified?.toEpochMilli() ?: -1}"
