package com.olyphototagger.app.write

import androidx.documentfile.provider.DocumentFile
import com.olyphototagger.app.dcim.CameraFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IncompleteWriteRecovererTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val parent get() = DocumentFile.fromFile(tempFolder.root)
    private fun docFor(name: String): DocumentFile = DocumentFile.fromFile(File(tempFolder.root, name))

    @Test
    fun `discardTemp deletes the temp file`() {
        tempFolder.newFile("P8080743.JPG.tmp")

        val result = IncompleteWriteRecoverer.discardTemp(docFor("P8080743.JPG.tmp"))

        assertEquals(RecoveryActionResult.Recovered, result)
        assertFalse(File(tempFolder.root, "P8080743.JPG.tmp").exists())
    }

    @Test
    fun `completeTagging resolves a real mid-crash state by promoting the temp and dropping the backup`() {
        // Reproduce exactly the on-disk state a real crash would leave, using the same
        // production code SafeFileSwap.swap() itself uses for this first step — not a
        // hand-reconstructed equivalent — then simply *not* continuing to the next step,
        // simulating "the process was killed right here".
        tempFolder.newFile("P8080743.JPG").writeText("original")
        tempFolder.newFile("P8080743.JPG.tmp").writeText("tagged!!")
        assertTrue(SafeFileSwap.renameOriginalToBackup(docFor("P8080743.JPG"), "P8080743.JPG.bak"))
        // Precondition check: this really did land in the AwaitingChoice state.
        assertFalse(File(tempFolder.root, "P8080743.JPG").exists())

        val result = IncompleteWriteRecoverer.completeTagging(
            parent, docFor("P8080743.JPG.tmp"), docFor("P8080743.JPG.bak"), "P8080743.JPG"
        )

        assertEquals(RecoveryActionResult.Recovered, result)
        assertEquals("tagged!!", File(tempFolder.root, "P8080743.JPG").readText())
        assertFalse(File(tempFolder.root, "P8080743.JPG.tmp").exists())
        assertFalse(File(tempFolder.root, "P8080743.JPG.bak").exists())
    }

    @Test
    fun `restoreOriginal resolves the same mid-crash state by restoring the backup and dropping the temp`() {
        tempFolder.newFile("P8080743.JPG").writeText("original")
        tempFolder.newFile("P8080743.JPG.tmp").writeText("tagged!!")
        assertTrue(SafeFileSwap.renameOriginalToBackup(docFor("P8080743.JPG"), "P8080743.JPG.bak"))

        val result = IncompleteWriteRecoverer.restoreOriginal(
            parent, docFor("P8080743.JPG.bak"), docFor("P8080743.JPG.tmp"), "P8080743.JPG"
        )

        assertEquals(RecoveryActionResult.Recovered, result)
        assertEquals("original", File(tempFolder.root, "P8080743.JPG").readText())
        assertFalse(File(tempFolder.root, "P8080743.JPG.tmp").exists())
        assertFalse(File(tempFolder.root, "P8080743.JPG.bak").exists())
    }

    @Test
    fun `restoreOriginal interrupted between its own two steps lands in the already-safe StaleTempOnly state, never a new dead end`() {
        // This is the direct regression test for the ordering bug caught during planning:
        // a naive "delete temp, then rename backup back" would leave neither name occupied
        // if interrupted between those two steps. restoreOriginal orders it the other way
        // — rename-and-verify first, delete second — so simulating a crash right after its
        // first step (by calling that exact step directly and stopping, same technique as
        // the completeTagging test above) must land somewhere already understood and safe.
        tempFolder.newFile("P8080743.JPG").writeText("original")
        tempFolder.newFile("P8080743.JPG.tmp").writeText("tagged!!")
        assertTrue(SafeFileSwap.renameOriginalToBackup(docFor("P8080743.JPG"), "P8080743.JPG.bak"))

        val outcome = SafeFileSwap.renameTempIntoPlace(
            parent, docFor("P8080743.JPG.bak"), "P8080743.JPG", expectedLength = docFor("P8080743.JPG.bak").length()
        )
        assertEquals(RenameTailOutcome.Verified, outcome)
        // Deliberately not calling temp.delete() — this is where restoreOriginal's own
        // "crash" is simulated.

        // The resulting state: original is correctly restored, but the temp is still
        // present and the backup is gone — exactly StaleTempOnly, which
        // IncompleteWriteDetector already classifies as safe/auto-resolvable (just a
        // redundant temp to discard), never a state with no recognizable file at all.
        assertEquals("original", File(tempFolder.root, "P8080743.JPG").readText())
        assertTrue(File(tempFolder.root, "P8080743.JPG.tmp").exists())
        assertFalse(File(tempFolder.root, "P8080743.JPG.bak").exists())
        val detected = IncompleteWriteDetector.detect(
            listOf(
                CameraFile("f://P8080743.JPG", "P8080743.JPG", "", 8, null),
                CameraFile("f://P8080743.JPG.tmp", "P8080743.JPG.tmp", "", 8, null)
            )
        )
        assertEquals(IncompleteWriteClassification.StaleTempOnly, detected.single().classification)
    }

    @Test
    fun `restoreFromBackupOnly renames the backup into place`() {
        tempFolder.newFile("P8080743.JPG.bak").writeText("original")

        val result = IncompleteWriteRecoverer.restoreFromBackupOnly(parent, docFor("P8080743.JPG.bak"), "P8080743.JPG")

        assertEquals(RecoveryActionResult.Recovered, result)
        assertEquals("original", File(tempFolder.root, "P8080743.JPG").readText())
    }

    @Test
    fun `keepOriginal discards a stray backup and leaves the original untouched`() {
        tempFolder.newFile("P8080743.JPG").writeText("original")
        tempFolder.newFile("P8080743.JPG.bak").writeText("stray")

        val result = IncompleteWriteRecoverer.keepOriginal(docFor("P8080743.JPG.bak"), temp = null)

        assertEquals(RecoveryActionResult.Recovered, result)
        assertEquals("original", File(tempFolder.root, "P8080743.JPG").readText())
        assertFalse(File(tempFolder.root, "P8080743.JPG.bak").exists())
    }

    @Test
    fun `keepOriginal discards both a stray backup and a stray temp when both are present`() {
        tempFolder.newFile("P8080743.JPG").writeText("original")
        tempFolder.newFile("P8080743.JPG.bak").writeText("stray backup")
        tempFolder.newFile("P8080743.JPG.tmp").writeText("stray temp")

        val result = IncompleteWriteRecoverer.keepOriginal(docFor("P8080743.JPG.bak"), docFor("P8080743.JPG.tmp"))

        assertEquals(RecoveryActionResult.Recovered, result)
        assertEquals("original", File(tempFolder.root, "P8080743.JPG").readText())
        assertFalse(File(tempFolder.root, "P8080743.JPG.bak").exists())
        assertFalse(File(tempFolder.root, "P8080743.JPG.tmp").exists())
    }

    @Test
    fun `keepBackup promotes the backup over the current original, never deleting the original until the promotion is verified`() {
        tempFolder.newFile("P8080743.JPG").writeText("current original")
        tempFolder.newFile("P8080743.JPG.bak").writeText("trusted backup")

        val result = IncompleteWriteRecoverer.keepBackup(
            parent, docFor("P8080743.JPG"), docFor("P8080743.JPG.bak"), temp = null, recoveredName = "P8080743.JPG"
        )

        assertEquals(RecoveryActionResult.Recovered, result)
        assertEquals("trusted backup", File(tempFolder.root, "P8080743.JPG").readText())
        assertFalse(File(tempFolder.root, "P8080743.JPG.bak").exists())
        assertFalse(File(tempFolder.root, "P8080743.JPG.recovery-displaced").exists())
    }

    @Test
    fun `keepBackup also discards a stray temp when all three files were present`() {
        tempFolder.newFile("P8080743.JPG").writeText("current original")
        tempFolder.newFile("P8080743.JPG.bak").writeText("trusted backup")
        tempFolder.newFile("P8080743.JPG.tmp").writeText("stray temp")

        val result = IncompleteWriteRecoverer.keepBackup(
            parent, docFor("P8080743.JPG"), docFor("P8080743.JPG.bak"), docFor("P8080743.JPG.tmp"), "P8080743.JPG"
        )

        assertEquals(RecoveryActionResult.Recovered, result)
        assertEquals("trusted backup", File(tempFolder.root, "P8080743.JPG").readText())
        assertFalse(File(tempFolder.root, "P8080743.JPG.tmp").exists())
    }

    @Test
    fun `keepTemp with no existing original is a plain verified rename`() {
        tempFolder.newFile("P8080743.JPG.tmp").writeText("orphaned temp")

        val result = IncompleteWriteRecoverer.keepTemp(parent, original = null, backup = null, docFor("P8080743.JPG.tmp"), "P8080743.JPG")

        assertEquals(RecoveryActionResult.Recovered, result)
        assertEquals("orphaned temp", File(tempFolder.root, "P8080743.JPG").readText())
    }

    @Test
    fun `keepTemp promotes over an existing original and discards the backup when all three were present`() {
        tempFolder.newFile("P8080743.JPG").writeText("current original")
        tempFolder.newFile("P8080743.JPG.bak").writeText("stray backup")
        tempFolder.newFile("P8080743.JPG.tmp").writeText("trusted temp")

        val result = IncompleteWriteRecoverer.keepTemp(
            parent, docFor("P8080743.JPG"), docFor("P8080743.JPG.bak"), docFor("P8080743.JPG.tmp"), "P8080743.JPG"
        )

        assertEquals(RecoveryActionResult.Recovered, result)
        assertEquals("trusted temp", File(tempFolder.root, "P8080743.JPG").readText())
        assertFalse(File(tempFolder.root, "P8080743.JPG.bak").exists())
    }

    @Test
    fun `promote refuses when a previous recovery attempt's holding file is still present, touching nothing`() {
        tempFolder.newFile("P8080743.JPG").writeText("current original")
        tempFolder.newFile("P8080743.JPG.bak").writeText("trusted backup")
        tempFolder.newFile("P8080743.JPG.recovery-displaced").writeText("leftover from a previous attempt")

        val result = IncompleteWriteRecoverer.keepBackup(
            parent, docFor("P8080743.JPG"), docFor("P8080743.JPG.bak"), temp = null, recoveredName = "P8080743.JPG"
        )

        assertTrue(result is RecoveryActionResult.ActionFailed)
        assertEquals("current original", File(tempFolder.root, "P8080743.JPG").readText())
        assertEquals("trusted backup", File(tempFolder.root, "P8080743.JPG.bak").readText())
    }
}
