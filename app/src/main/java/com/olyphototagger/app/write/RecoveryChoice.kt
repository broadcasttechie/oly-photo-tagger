package com.olyphototagger.app.write

/** An action [IncompleteWriteRecoverer] can take to resolve one [IncompleteWrite]. */
enum class RecoveryChoice {
    /** Delete the temp file, leaving whatever else is present untouched. */
    DISCARD_TEMP,

    /** Rename the backup back to the recovered name, discarding the temp. */
    RESTORE_ORIGINAL,

    /** Rename the temp into the recovered name, discarding the backup. */
    COMPLETE_TAGGING,

    /** Rename the backup into the recovered name — there's no temp to discard. */
    RESTORE_FROM_BACKUP,

    /** Keep the file currently under the recovered name as-is; discard the rest. */
    FLAG_KEEP_ORIGINAL,

    /** Rename the backup over the recovered name; discard the rest. */
    FLAG_KEEP_BACKUP,

    /** Rename the temp over the recovered name; discard the rest. */
    FLAG_KEEP_TEMP
}

/**
 * Which [RecoveryChoice]s make sense for a given [IncompleteWriteClassification], and
 * which one (if any) is worth suggesting as the default. Pulled out of the recovery UI so
 * it's plain, unit-testable decision logic — same pattern as
 * [com.olyphototagger.app.settings.ActiveGpsSourceResolver] and
 * [com.olyphototagger.app.pipeline.PairFilter] elsewhere in this codebase.
 */
object RecoveryOptions {

    fun choicesFor(classification: IncompleteWriteClassification): List<RecoveryChoice> = when (classification) {
        IncompleteWriteClassification.StaleTempOnly -> listOf(RecoveryChoice.DISCARD_TEMP)
        IncompleteWriteClassification.AwaitingChoice -> listOf(RecoveryChoice.COMPLETE_TAGGING, RecoveryChoice.RESTORE_ORIGINAL)
        IncompleteWriteClassification.BackupOnly -> listOf(RecoveryChoice.RESTORE_FROM_BACKUP)
        IncompleteWriteClassification.OriginalAndBackupPresent ->
            listOf(RecoveryChoice.FLAG_KEEP_ORIGINAL, RecoveryChoice.FLAG_KEEP_BACKUP)
        IncompleteWriteClassification.AllThreePresent ->
            listOf(RecoveryChoice.FLAG_KEEP_ORIGINAL, RecoveryChoice.FLAG_KEEP_BACKUP, RecoveryChoice.FLAG_KEEP_TEMP)
        // Provenance is genuinely unknown here (could predate this feature entirely), so
        // — unlike StaleTempOnly's single unambiguous DISCARD_TEMP — this offers a real
        // keep-or-discard choice rather than assuming either answer.
        IncompleteWriteClassification.TempOnly -> listOf(RecoveryChoice.FLAG_KEEP_TEMP, RecoveryChoice.DISCARD_TEMP)
    }

    /**
     * Only [IncompleteWriteClassification.AwaitingChoice] gets a suggested default — it's
     * the only classification with more than one real choice where one option is
     * genuinely safer to recommend: the temp file was already fully verified *before* the
     * backup-rename ever began, so completing the tagging loses nothing that wasn't
     * already confirmed good. Every other classification either has exactly one choice
     * (nothing to default between) or is a "we can't tell, you decide" case where
     * suggesting one option over another would be a guess, not a recommendation.
     */
    fun suggestedDefault(classification: IncompleteWriteClassification): RecoveryChoice? =
        if (classification == IncompleteWriteClassification.AwaitingChoice) RecoveryChoice.COMPLETE_TAGGING else null
}
