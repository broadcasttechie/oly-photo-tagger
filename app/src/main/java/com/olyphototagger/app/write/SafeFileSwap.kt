package com.olyphototagger.app.write

import androidx.documentfile.provider.DocumentFile

/**
 * Result of [SafeFileSwap.swap]. A caller that only cares about "did the file end up
 * correctly tagged" should treat only [Success] as success — everything else means the
 * write did not complete and the file needs attention.
 */
sealed interface SwapResult {
    /**
     * The swap completed and the final file is confirmed correct. [strayBackupFileName] is
     * non-null only if the (now-redundant) backup couldn't be deleted — the real file is
     * already confirmed correct at that point, so this is never a reason to treat the
     * write as failed, just something to clean up.
     */
    data class Success(val strayBackupFileName: String?) : SwapResult

    /** The original was never touched — still present under its real name, untouched. */
    data class BackupRenameFailed(val reason: String) : SwapResult

    /** The original is now the backup; the temp file's rename into place failed outright. */
    data class FinalRenameFailed(val reason: String) : SwapResult

    /** The rename call itself reported success, but re-checking couldn't confirm it. */
    data class FinalRenameUnverified(val reason: String) : SwapResult
}

/** Result of [SafeFileSwap.renameTempIntoPlace] on its own. */
sealed interface RenameTailOutcome {
    data object Verified : RenameTailOutcome
    data object RenameCallFailed : RenameTailOutcome
    data class VerificationFailed(val reason: String) : RenameTailOutcome
}

/**
 * The crash-safe replacement sequence used in place of a plain delete-then-rename: rename
 * the original to a backup, rename the temp file into the original's name, verify that
 * rename, then delete the backup. At every point in this sequence at least one file exists
 * under a name a person or the scanner can recognize — either the original (as the
 * backup) or the finished result (under its real name) — never the "deleted, not yet
 * renamed" window a plain delete-then-rename leaves if interrupted.
 *
 * [renameOriginalToBackup], [renameTempIntoPlace], and [deleteBackup] are exposed as their
 * own functions, not private to [swap], for two reasons: [IncompleteWriteRecoverer]'s
 * "complete tagging"/"restore original" actions reuse [renameTempIntoPlace] and
 * [deleteBackup] instead of reimplementing them, and tests need to stop this sequence
 * partway through — after [renameOriginalToBackup] but before [renameTempIntoPlace] — to
 * reproduce exactly the on-disk state a real crash in that window would leave, using this
 * exact production code path rather than a hand-reconstructed equivalent.
 */
object SafeFileSwap {

    fun swap(parent: DocumentFile, original: DocumentFile, temp: DocumentFile, originalName: String): SwapResult {
        val backupName = GpsWriteSupport.backupNameFor(originalName)
        // Defense in depth: GpsExifWriter already refuses to start a write when a backup
        // exists, but re-checking here means this function is safe to call on its own.
        if (parent.findFile(backupName) != null) {
            return SwapResult.BackupRenameFailed("A backup already exists at $backupName")
        }

        val expectedLength = temp.length()
        if (!renameOriginalToBackup(original, backupName)) {
            return SwapResult.BackupRenameFailed("Could not rename $originalName to $backupName")
        }

        when (val outcome = renameTempIntoPlace(parent, temp, originalName, expectedLength)) {
            RenameTailOutcome.RenameCallFailed ->
                return SwapResult.FinalRenameFailed("Renaming the temp file to $originalName failed")
            is RenameTailOutcome.VerificationFailed ->
                return SwapResult.FinalRenameUnverified(outcome.reason)
            RenameTailOutcome.Verified -> Unit
        }

        val deleted = deleteBackup(parent.findFile(backupName))
        return SwapResult.Success(strayBackupFileName = if (deleted) null else backupName)
    }

    /** Renames [original] to [backupName] in place. */
    fun renameOriginalToBackup(original: DocumentFile, backupName: String): Boolean = original.renameTo(backupName)

    /**
     * Renames [temp] to [finalName] within [parent], then re-checks via a *fresh*
     * `parent.findFile(finalName)` lookup that the result exists with [expectedLength] —
     * not by trusting [temp]'s own post-rename state. AndroidX's own documentation warns a
     * caller should re-resolve via the parent after a rename rather than assume the
     * original handle's Uri/state still reflects it (a real SAF provider can hand back an
     * entirely different content Uri on rename); this applies that rule.
     */
    fun renameTempIntoPlace(parent: DocumentFile, temp: DocumentFile, finalName: String, expectedLength: Long): RenameTailOutcome {
        if (!temp.renameTo(finalName)) {
            return RenameTailOutcome.RenameCallFailed
        }
        val renamed = parent.findFile(finalName)
            ?: return RenameTailOutcome.VerificationFailed("No file named $finalName found after rename")
        if (renamed.length() != expectedLength) {
            return RenameTailOutcome.VerificationFailed(
                "Renamed file's size (${renamed.length()}) doesn't match the temp file's size ($expectedLength) before rename"
            )
        }
        return RenameTailOutcome.Verified
    }

    /** True if [backup] is gone afterward — a null [backup] (already gone) counts as true. */
    fun deleteBackup(backup: DocumentFile?): Boolean = backup?.delete() ?: true
}
