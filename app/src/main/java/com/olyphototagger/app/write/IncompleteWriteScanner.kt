package com.olyphototagger.app.write

import androidx.documentfile.provider.DocumentFile
import com.olyphototagger.app.dcim.CameraFile
import com.olyphototagger.app.dcim.DcimScanner

/**
 * Output of [IncompleteWriteScanner.scan] — carries enough to both display each
 * [IncompleteWrite] and later [IncompleteWriteScanner.resolve] it, mirroring
 * [com.olyphototagger.app.pipeline.ScanResult]'s own `resolve(CameraFile)` pattern.
 */
class IncompleteWriteScanResult(
    val items: List<IncompleteWrite>,
    private val resolver: (CameraFile) -> DocumentFile?
) {
    fun resolve(file: CameraFile): DocumentFile? = resolver(file)
}

/**
 * Thin wiring tying [DcimScanner]'s real folder listing to [IncompleteWriteDetector]'s pure
 * classification and [IncompleteWriteRecoverer]'s real recovery actions — the
 * crash-recovery counterpart to `GeotagOrchestrator.classify()`, kept as its own
 * standalone class rather than folded into `GeotagOrchestrator` since it needs none of
 * that class's pairer/GPS-source/interpolator/writer dependencies. Not itself
 * exhaustively unit tested (thin wiring, same footing as `GeotagOrchestrator.classify()`
 * today) — confidence rests on [IncompleteWriteDetector] and [IncompleteWriteRecoverer]'s
 * own tests.
 */
class IncompleteWriteScanner(private val dcimScanner: DcimScanner) {

    suspend fun scan(dcimRoot: DocumentFile): IncompleteWriteScanResult {
        val scanResult = dcimScanner.scan(dcimRoot)
        val items = IncompleteWriteDetector.detect(scanResult.files)
        return IncompleteWriteScanResult(items, scanResult::resolve)
    }

    /**
     * Resolves each of [item]'s original/temp/backup [CameraFile]s back to real
     * [DocumentFile]s via [scanResult], then dispatches to the [IncompleteWriteRecoverer]
     * function [choice] calls for. [choice] must be one [RecoveryOptions.choicesFor] would
     * actually offer for `item.classification` — anything else fails cleanly rather than
     * touching files based on a mismatched choice.
     */
    fun resolve(scanResult: IncompleteWriteScanResult, item: IncompleteWrite, choice: RecoveryChoice): RecoveryActionResult {
        val original = item.original?.let(scanResult::resolve)
        val temp = item.temp?.let(scanResult::resolve)
        val backup = item.backup?.let(scanResult::resolve)
        val parent = (original ?: temp ?: backup)?.parentFile
            ?: return RecoveryActionResult.ActionFailed("Could not resolve a parent folder for ${item.recoveredName}")
        val recoveredName = item.recoveredName

        return when (choice) {
            RecoveryChoice.DISCARD_TEMP -> temp
                ?.let(IncompleteWriteRecoverer::discardTemp)
                ?: RecoveryActionResult.ActionFailed("No temp file to discard")

            RecoveryChoice.COMPLETE_TAGGING -> if (temp != null && backup != null) {
                IncompleteWriteRecoverer.completeTagging(parent, temp, backup, recoveredName)
            } else {
                RecoveryActionResult.ActionFailed("Missing temp or backup for completeTagging")
            }

            RecoveryChoice.RESTORE_ORIGINAL -> if (backup != null && temp != null) {
                IncompleteWriteRecoverer.restoreOriginal(parent, backup, temp, recoveredName)
            } else {
                RecoveryActionResult.ActionFailed("Missing backup or temp for restoreOriginal")
            }

            RecoveryChoice.RESTORE_FROM_BACKUP -> backup
                ?.let { IncompleteWriteRecoverer.restoreFromBackupOnly(parent, it, recoveredName) }
                ?: RecoveryActionResult.ActionFailed("No backup file to restore")

            RecoveryChoice.FLAG_KEEP_ORIGINAL -> IncompleteWriteRecoverer.keepOriginal(backup, temp)

            RecoveryChoice.FLAG_KEEP_BACKUP -> if (original != null && backup != null) {
                IncompleteWriteRecoverer.keepBackup(parent, original, backup, temp, recoveredName)
            } else {
                RecoveryActionResult.ActionFailed("Missing original or backup for keepBackup")
            }

            RecoveryChoice.FLAG_KEEP_TEMP -> temp
                ?.let { IncompleteWriteRecoverer.keepTemp(parent, original, backup, it, recoveredName) }
                ?: RecoveryActionResult.ActionFailed("No temp file to keep")
        }
    }
}
