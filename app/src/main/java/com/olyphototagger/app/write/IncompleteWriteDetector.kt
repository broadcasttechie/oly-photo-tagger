package com.olyphototagger.app.write

import com.olyphototagger.app.dcim.CameraFile

/**
 * Which reachable combination of {original, temp, backup} a group of files represents.
 * One case per row of the recovery state table worked out alongside [SafeFileSwap] — see
 * that class's doc for the full reasoning behind each.
 */
sealed interface IncompleteWriteClassification {
    /** Original + a stray temp, no backup — crash during early write/verify, before the
     *  backup-rename ever started. The original is untouched and correct. */
    data object StaleTempOnly : IncompleteWriteClassification

    /** Temp + backup, no original — the core "mid-flight" case: a crash between the
     *  backup-rename succeeding and the final rename completing. */
    data object AwaitingChoice : IncompleteWriteClassification

    /** Only a backup — a non-atomic rename produced neither name, or the temp was removed
     *  externally mid-window. The backup is the only known-good copy. */
    data object BackupOnly : IncompleteWriteClassification

    /** Original + backup, no temp — the file is already correctly tagged and only the
     *  backup's own cleanup delete failed (mirrors the in-flow case
     *  [GpsExifWriteResult.Written.strayBackupFileName] already covers), or external
     *  interference. A *later* scan has no memory of which, so this is flagged rather than
     *  silently resolved either way. */
    data object OriginalAndBackupPresent : IncompleteWriteClassification

    /** All three present simultaneously — shouldn't arise from this app's own write flow. */
    data object AllThreePresent : IncompleteWriteClassification

    /** Only a stray temp, no original, no backup — ambiguous; could predate this feature. */
    data object TempOnly : IncompleteWriteClassification
}

/** One recoverable (or flaggable) group of files sharing a folder and recovered base name. */
data class IncompleteWrite(
    val folderName: String,
    val recoveredName: String,
    val original: CameraFile?,
    val temp: CameraFile?,
    val backup: CameraFile?,
    val classification: IncompleteWriteClassification
)

/**
 * Finds `.tmp`/`.bak` artifacts matching [GpsWriteSupport]'s naming convention and groups
 * each with whatever else shares its recovered name, so [IncompleteWriteRecoverer] has
 * enough to act on. A purpose-built pass rather than reusing
 * [com.olyphototagger.app.dcim.PhotoPairer]'s `ignored` bucket: that bucket already lumps
 * `.tmp`/`.bak` files in with GPSLOG/video/thumbnail files undifferentiated, and the
 * semantics needed here — exactly which combination of files exists, what action to offer
 * — are far more specific than "was ignored."
 */
object IncompleteWriteDetector {

    private enum class Role { ORIGINAL, TEMP, BACKUP }

    private class Group {
        var recoveredName: String? = null
        var original: CameraFile? = null
        var temp: CameraFile? = null
        var backup: CameraFile? = null
    }

    fun detect(files: List<CameraFile>): List<IncompleteWrite> {
        val groups = LinkedHashMap<Pair<String, String>, Group>()

        for (file in files) {
            val (recoveredName, role) = recoveredNameAndRole(file)
            val key = file.folderName to recoveredName.uppercase()
            val group = groups.getOrPut(key) { Group() }
            group.recoveredName = group.recoveredName ?: recoveredName
            when (role) {
                Role.ORIGINAL -> group.original = file
                Role.TEMP -> group.temp = file
                Role.BACKUP -> group.backup = file
            }
        }

        return groups.entries.mapNotNull { (key, group) ->
            val classification = classify(
                hasOriginal = group.original != null,
                hasTemp = group.temp != null,
                hasBackup = group.backup != null
            ) ?: return@mapNotNull null // original only, or nothing at all — not a recovery case
            IncompleteWrite(
                folderName = key.first,
                recoveredName = requireNotNull(group.recoveredName),
                original = group.original,
                temp = group.temp,
                backup = group.backup,
                classification = classification
            )
        }
    }

    private fun recoveredNameAndRole(file: CameraFile): Pair<String, Role> {
        val artifact = GpsWriteSupport.parseArtifactName(file.displayName) ?: return file.displayName to Role.ORIGINAL
        return artifact.recoveredName to if (artifact.isTemp) Role.TEMP else Role.BACKUP
    }

    private fun classify(hasOriginal: Boolean, hasTemp: Boolean, hasBackup: Boolean): IncompleteWriteClassification? = when {
        hasOriginal && hasTemp && hasBackup -> IncompleteWriteClassification.AllThreePresent
        hasOriginal && hasTemp -> IncompleteWriteClassification.StaleTempOnly
        hasOriginal && hasBackup -> IncompleteWriteClassification.OriginalAndBackupPresent
        hasTemp && hasBackup -> IncompleteWriteClassification.AwaitingChoice
        hasBackup -> IncompleteWriteClassification.BackupOnly
        hasTemp -> IncompleteWriteClassification.TempOnly
        else -> null
    }
}
