package com.olyphototagger.app.write

import androidx.documentfile.provider.DocumentFile

sealed interface RecoveryActionResult {
    data object Recovered : RecoveryActionResult
    data class ActionFailed(val reason: String) : RecoveryActionResult
}

/**
 * Carries out a [RecoveryChoice] against the real files an [IncompleteWrite] found.
 * Every action here follows the same discipline the rest of this redesign is built on:
 * never destroy a file until whatever's meant to replace it is confirmed in place — so a
 * crash *during recovery itself* can only ever land back in an already-understood, still
 * recoverable state, never a new dead end.
 */
object IncompleteWriteRecoverer {

    /** [RecoveryChoice.DISCARD_TEMP] — used both when the original is already known-good
     *  (a stray temp is pure cleanup) and as the "no, don't trust it" half of
     *  [IncompleteWriteClassification.TempOnly]'s genuine keep-or-discard choice. */
    fun discardTemp(temp: DocumentFile): RecoveryActionResult =
        if (temp.delete()) RecoveryActionResult.Recovered
        else RecoveryActionResult.ActionFailed("Could not delete the temp file")

    /**
     * [RecoveryChoice.COMPLETE_TAGGING] — reuses [SafeFileSwap]'s own tested tail: rename
     * the temp into the recovered name, verify it, only then delete the backup. The temp
     * was already fully verified *before* the original write's backup-rename ever began,
     * so this loses nothing that wasn't already confirmed good.
     */
    fun completeTagging(parent: DocumentFile, temp: DocumentFile, backup: DocumentFile, recoveredName: String): RecoveryActionResult {
        val expectedLength = temp.length()
        return when (val outcome = SafeFileSwap.renameTempIntoPlace(parent, temp, recoveredName, expectedLength)) {
            RenameTailOutcome.Verified -> {
                SafeFileSwap.deleteBackup(backup)
                RecoveryActionResult.Recovered
            }
            RenameTailOutcome.RenameCallFailed -> RecoveryActionResult.ActionFailed("Could not rename the temp file into place")
            is RenameTailOutcome.VerificationFailed -> RecoveryActionResult.ActionFailed(outcome.reason)
        }
    }

    /**
     * [RecoveryChoice.RESTORE_ORIGINAL] — renames the backup back to the recovered name
     * and verifies it **before** deleting the temp, deliberately the reverse order of a
     * naive "delete temp, then rename backup back": that ordering would reintroduce the
     * exact bug this whole redesign exists to fix (a crash between those two steps would
     * leave neither name occupied). This way, a crash after the rename but before the
     * delete just leaves a redundant [temp] sitting next to the now-correct file — a
     * already-understood, harmless leftover, not a new dead end.
     */
    fun restoreOriginal(parent: DocumentFile, backup: DocumentFile, temp: DocumentFile, recoveredName: String): RecoveryActionResult {
        val expectedLength = backup.length()
        return when (val outcome = SafeFileSwap.renameTempIntoPlace(parent, backup, recoveredName, expectedLength)) {
            RenameTailOutcome.Verified -> {
                temp.delete()
                RecoveryActionResult.Recovered
            }
            RenameTailOutcome.RenameCallFailed -> RecoveryActionResult.ActionFailed("Could not rename the backup back into place")
            is RenameTailOutcome.VerificationFailed -> RecoveryActionResult.ActionFailed(outcome.reason)
        }
    }

    /** [RecoveryChoice.RESTORE_FROM_BACKUP] — no temp to worry about; a plain verified rename. */
    fun restoreFromBackupOnly(parent: DocumentFile, backup: DocumentFile, recoveredName: String): RecoveryActionResult {
        val expectedLength = backup.length()
        return when (val outcome = SafeFileSwap.renameTempIntoPlace(parent, backup, recoveredName, expectedLength)) {
            RenameTailOutcome.Verified -> RecoveryActionResult.Recovered
            RenameTailOutcome.RenameCallFailed -> RecoveryActionResult.ActionFailed("Could not rename the backup into place")
            is RenameTailOutcome.VerificationFailed -> RecoveryActionResult.ActionFailed(outcome.reason)
        }
    }

    /** [RecoveryChoice.FLAG_KEEP_ORIGINAL] — the file already under the recovered name is
     *  trusted as-is; everything else in the group is redundant and discarded. */
    fun keepOriginal(backup: DocumentFile?, temp: DocumentFile?): RecoveryActionResult {
        backup?.delete()
        temp?.delete()
        return RecoveryActionResult.Recovered
    }

    /** [RecoveryChoice.FLAG_KEEP_BACKUP] — the backup is trusted over whatever currently
     *  occupies the recovered name; [original] is safely displaced, never deleted, until
     *  the backup is confirmed promoted into place. */
    fun keepBackup(parent: DocumentFile, original: DocumentFile, backup: DocumentFile, temp: DocumentFile?, recoveredName: String): RecoveryActionResult {
        val result = promote(parent, fileToPromote = backup, occupant = original, recoveredName = recoveredName)
        if (result is RecoveryActionResult.Recovered) {
            temp?.delete()
        }
        return result
    }

    /** [RecoveryChoice.FLAG_KEEP_TEMP] — the temp is trusted over whatever currently
     *  occupies the recovered name (if anything — [original] is null for
     *  [IncompleteWriteClassification.TempOnly], where this is a plain verified rename). */
    fun keepTemp(parent: DocumentFile, original: DocumentFile?, backup: DocumentFile?, temp: DocumentFile, recoveredName: String): RecoveryActionResult {
        val result = promote(parent, fileToPromote = temp, occupant = original, recoveredName = recoveredName)
        if (result is RecoveryActionResult.Recovered) {
            backup?.delete()
        }
        return result
    }

    /**
     * Shared "make [fileToPromote] the file at [recoveredName]" primitive for the two
     * FLAG_KEEP_* actions that might need to displace an existing occupant. When
     * [occupant] is null this is just a verified rename (via [SafeFileSwap.renameTempIntoPlace]).
     * When it isn't, [occupant] is moved to a holding name first and only deleted after
     * [fileToPromote] is confirmed renamed and verified — never the other way around, for
     * the same reason [restoreOriginal] orders its own two steps the way it does.
     */
    private fun promote(parent: DocumentFile, fileToPromote: DocumentFile, occupant: DocumentFile?, recoveredName: String): RecoveryActionResult {
        if (occupant == null) {
            val expectedLength = fileToPromote.length()
            return when (val outcome = SafeFileSwap.renameTempIntoPlace(parent, fileToPromote, recoveredName, expectedLength)) {
                RenameTailOutcome.Verified -> RecoveryActionResult.Recovered
                RenameTailOutcome.RenameCallFailed -> RecoveryActionResult.ActionFailed("Could not rename into place")
                is RenameTailOutcome.VerificationFailed -> RecoveryActionResult.ActionFailed(outcome.reason)
            }
        }

        val holdingName = "$recoveredName.recovery-displaced"
        if (parent.findFile(holdingName) != null) {
            return RecoveryActionResult.ActionFailed(
                "A previous recovery attempt's holding file ($holdingName) is already present — resolve that first"
            )
        }
        if (!occupant.renameTo(holdingName)) {
            return RecoveryActionResult.ActionFailed("Could not move the current file at $recoveredName aside")
        }

        val expectedLength = fileToPromote.length()
        return when (val outcome = SafeFileSwap.renameTempIntoPlace(parent, fileToPromote, recoveredName, expectedLength)) {
            RenameTailOutcome.Verified -> {
                parent.findFile(holdingName)?.delete()
                RecoveryActionResult.Recovered
            }
            RenameTailOutcome.RenameCallFailed, is RenameTailOutcome.VerificationFailed ->
                RecoveryActionResult.ActionFailed(
                    "Could not promote the chosen file into place — the previous file is safely preserved as $holdingName"
                )
        }
    }
}
