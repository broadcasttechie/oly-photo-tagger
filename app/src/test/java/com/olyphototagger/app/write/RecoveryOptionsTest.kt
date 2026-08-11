package com.olyphototagger.app.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryOptionsTest {

    @Test
    fun `StaleTempOnly offers only DISCARD_TEMP`() {
        assertEquals(
            listOf(RecoveryChoice.DISCARD_TEMP),
            RecoveryOptions.choicesFor(IncompleteWriteClassification.StaleTempOnly)
        )
        assertNull(RecoveryOptions.suggestedDefault(IncompleteWriteClassification.StaleTempOnly))
    }

    @Test
    fun `AwaitingChoice offers COMPLETE_TAGGING and RESTORE_ORIGINAL`() {
        assertEquals(
            listOf(RecoveryChoice.COMPLETE_TAGGING, RecoveryChoice.RESTORE_ORIGINAL),
            RecoveryOptions.choicesFor(IncompleteWriteClassification.AwaitingChoice)
        )
    }

    @Test
    fun `completeTagging is the suggested default for AwaitingChoice because the temp was already verified`() {
        assertEquals(
            RecoveryChoice.COMPLETE_TAGGING,
            RecoveryOptions.suggestedDefault(IncompleteWriteClassification.AwaitingChoice)
        )
    }

    @Test
    fun `BackupOnly offers only RESTORE_FROM_BACKUP`() {
        assertEquals(
            listOf(RecoveryChoice.RESTORE_FROM_BACKUP),
            RecoveryOptions.choicesFor(IncompleteWriteClassification.BackupOnly)
        )
        assertNull(RecoveryOptions.suggestedDefault(IncompleteWriteClassification.BackupOnly))
    }

    @Test
    fun `OriginalAndBackupPresent offers a choice between keeping the original or the backup, no default`() {
        assertEquals(
            listOf(RecoveryChoice.FLAG_KEEP_ORIGINAL, RecoveryChoice.FLAG_KEEP_BACKUP),
            RecoveryOptions.choicesFor(IncompleteWriteClassification.OriginalAndBackupPresent)
        )
        assertNull(RecoveryOptions.suggestedDefault(IncompleteWriteClassification.OriginalAndBackupPresent))
    }

    @Test
    fun `AllThreePresent offers a choice between all three files, no default`() {
        assertEquals(
            listOf(RecoveryChoice.FLAG_KEEP_ORIGINAL, RecoveryChoice.FLAG_KEEP_BACKUP, RecoveryChoice.FLAG_KEEP_TEMP),
            RecoveryOptions.choicesFor(IncompleteWriteClassification.AllThreePresent)
        )
        assertNull(RecoveryOptions.suggestedDefault(IncompleteWriteClassification.AllThreePresent))
    }

    @Test
    fun `TempOnly offers a genuine keep-or-discard choice since its provenance is unknown, no default`() {
        assertEquals(
            listOf(RecoveryChoice.FLAG_KEEP_TEMP, RecoveryChoice.DISCARD_TEMP),
            RecoveryOptions.choicesFor(IncompleteWriteClassification.TempOnly)
        )
        assertNull(RecoveryOptions.suggestedDefault(IncompleteWriteClassification.TempOnly))
    }

    @Test
    fun `every classification has at least one choice`() {
        val allClassifications = listOf(
            IncompleteWriteClassification.StaleTempOnly,
            IncompleteWriteClassification.AwaitingChoice,
            IncompleteWriteClassification.BackupOnly,
            IncompleteWriteClassification.OriginalAndBackupPresent,
            IncompleteWriteClassification.AllThreePresent,
            IncompleteWriteClassification.TempOnly
        )
        allClassifications.forEach { classification ->
            assert(RecoveryOptions.choicesFor(classification).isNotEmpty()) {
                "$classification has no recovery choices at all — a user would be stuck"
            }
        }
    }

    @Test
    fun `unambiguousChoiceFor returns the single choice for StaleTempOnly and BackupOnly`() {
        assertEquals(
            RecoveryChoice.DISCARD_TEMP,
            RecoveryOptions.unambiguousChoiceFor(IncompleteWriteClassification.StaleTempOnly)
        )
        assertEquals(
            RecoveryChoice.RESTORE_FROM_BACKUP,
            RecoveryOptions.unambiguousChoiceFor(IncompleteWriteClassification.BackupOnly)
        )
    }

    @Test
    fun `unambiguousChoiceFor is null for every classification offering a real decision`() {
        // Including AwaitingChoice, despite it having a suggestedDefault — a suggestion is
        // still choosing on the user's behalf, not the only possible outcome, so it must
        // never be eligible for a bulk "resolve all" shortcut.
        listOf(
            IncompleteWriteClassification.AwaitingChoice,
            IncompleteWriteClassification.OriginalAndBackupPresent,
            IncompleteWriteClassification.AllThreePresent,
            IncompleteWriteClassification.TempOnly
        ).forEach { classification ->
            assertNull(
                "$classification has more than one real choice and must not be bulk-eligible",
                RecoveryOptions.unambiguousChoiceFor(classification)
            )
        }
    }
}
