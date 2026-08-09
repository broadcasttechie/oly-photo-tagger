package com.olyphototagger.app.dcim

data class PairingResult(
    val pairs: List<PhotoPair>,
    /** Non-JPEG/ORF files (GPSLOG, video, thumbnails, etc.) — not part of the pipeline. */
    val ignored: List<CameraFile>,
    /** Files where more than one JPEG or more than one RAW shared a folder+base name —
     *  too ambiguous to auto-pair, surfaced for manual handling rather than guessed at. */
    val conflicts: List<CameraFile>
)

/**
 * Groups camera files into RAW+JPEG pairs.
 *
 * Grouping key is (folder, base name) rather than base name alone: the camera resets its
 * filename sequence in each new DCIM subfolder (e.g. 100OLYMP, 101OLYMP), so the same base
 * name in different folders is an unrelated photo, not a pair. Matching is case-insensitive
 * on the base name since extension casing (.jpg vs .JPG) shouldn't affect pairing.
 */
object PhotoPairer {

    fun pair(files: List<CameraFile>): PairingResult {
        val ignored = mutableListOf<CameraFile>()
        val grouped = linkedMapOf<Pair<String, String>, MutableMap<PhotoKind, MutableList<CameraFile>>>()

        for (file in files) {
            if (file.kind == PhotoKind.OTHER) {
                ignored += file
                continue
            }
            val key = file.folderName to file.baseName.uppercase()
            grouped.getOrPut(key) { mutableMapOf() }
                .getOrPut(file.kind) { mutableListOf() } += file
        }

        val pairs = mutableListOf<PhotoPair>()
        val conflicts = mutableListOf<CameraFile>()

        for ((key, byKind) in grouped) {
            val jpegs = byKind[PhotoKind.JPEG].orEmpty()
            val raws = byKind[PhotoKind.RAW].orEmpty()

            if (jpegs.size > 1 || raws.size > 1) {
                conflicts += jpegs + raws
                continue
            }

            pairs += PhotoPair(
                folderName = key.first,
                baseName = (jpegs.firstOrNull() ?: raws.first()).baseName,
                jpeg = jpegs.firstOrNull(),
                raw = raws.firstOrNull()
            )
        }

        return PairingResult(pairs, ignored, conflicts)
    }
}
