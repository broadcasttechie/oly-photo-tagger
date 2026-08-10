package com.olyphototagger.app.write

import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafeFileSwapTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun docFor(name: String): DocumentFile = DocumentFile.fromFile(File(tempFolder.root, name))

    /**
     * Not testing SafeFileSwap itself — confirms the whole approach this test class (and
     * later IncompleteWriteRecoverer/IncompleteWriteDetector tests) relies on actually
     * works: `DocumentFile.fromFile()` delegates straight to `java.io.File`, so its
     * rename/delete/exists/length can be exercised in a plain JVM unit test against real
     * files, with no Robolectric and no Android instrumentation needed. If this ever stops
     * being true, this fails loudly and immediately rather than every other test in this
     * file failing for a confusing reason.
     */
    @Test
    fun `DocumentFile fromFile delegates to real java-io-File without Android instrumentation`() {
        val file = tempFolder.newFile("hello.txt").apply { writeText("hi") }
        val doc = DocumentFile.fromFile(file)

        assertTrue(doc.exists())
        assertEquals(2L, doc.length())
        assertTrue(doc.renameTo("renamed.txt"))
        assertTrue(File(tempFolder.root, "renamed.txt").exists())
        assertFalse(file.exists())
    }

    @Test
    fun `swap renames original to backup, temp into place, and cleans up the backup`() {
        val parent = DocumentFile.fromFile(tempFolder.root)
        tempFolder.newFile("P8080743.JPG").writeText("original")
        tempFolder.newFile("P8080743.JPG.tmp").writeText("tagged!!")

        val result = SafeFileSwap.swap(
            parent = parent,
            original = docFor("P8080743.JPG"),
            temp = docFor("P8080743.JPG.tmp"),
            originalName = "P8080743.JPG"
        )

        assertEquals(SwapResult.Success(strayBackupFileName = null), result)
        assertEquals("tagged!!", File(tempFolder.root, "P8080743.JPG").readText())
        assertFalse(File(tempFolder.root, "P8080743.JPG.tmp").exists())
        assertFalse(File(tempFolder.root, "P8080743.JPG.bak").exists())
    }

    @Test
    fun `a pre-existing backup refuses the swap without touching anything`() {
        val parent = DocumentFile.fromFile(tempFolder.root)
        tempFolder.newFile("P8080743.JPG").writeText("original")
        tempFolder.newFile("P8080743.JPG.tmp").writeText("tagged!!")
        tempFolder.newFile("P8080743.JPG.bak").writeText("stray backup from an earlier crash")

        val result = SafeFileSwap.swap(
            parent = parent,
            original = docFor("P8080743.JPG"),
            temp = docFor("P8080743.JPG.tmp"),
            originalName = "P8080743.JPG"
        )

        assertTrue(result is SwapResult.BackupRenameFailed)
        assertEquals("original", File(tempFolder.root, "P8080743.JPG").readText())
        assertEquals("tagged!!", File(tempFolder.root, "P8080743.JPG.tmp").readText())
        assertEquals("stray backup from an earlier crash", File(tempFolder.root, "P8080743.JPG.bak").readText())
    }

    @Test
    fun `backup rename failing leaves the original completely untouched`() {
        val parent = DocumentFile.fromFile(tempFolder.root)
        tempFolder.newFile("P8080743.JPG.tmp").writeText("tagged!!")
        // "original" points at a file that was never created — renameTo() on a
        // non-existent source reliably returns false with real java.io.File semantics,
        // without needing filesystem-permission tricks that could be flaky across CI.
        val missingOriginal = docFor("P8080743.JPG")

        val result = SafeFileSwap.swap(
            parent = parent,
            original = missingOriginal,
            temp = docFor("P8080743.JPG.tmp"),
            originalName = "P8080743.JPG"
        )

        assertTrue(result is SwapResult.BackupRenameFailed)
        assertFalse(File(tempFolder.root, "P8080743.JPG.bak").exists())
        assertEquals("tagged!!", File(tempFolder.root, "P8080743.JPG.tmp").readText())
    }

    @Test
    fun `final rename failing leaves the original recoverable as the backup`() {
        val parent = DocumentFile.fromFile(tempFolder.root)
        tempFolder.newFile("P8080743.JPG").writeText("original")
        // No .tmp file actually exists, so renaming it into place will fail — but the
        // original -> backup rename (the step before it) has already succeeded by then.
        val missingTemp = docFor("P8080743.JPG.tmp")

        val result = SafeFileSwap.swap(
            parent = parent,
            original = docFor("P8080743.JPG"),
            temp = missingTemp,
            originalName = "P8080743.JPG"
        )

        assertTrue(result is SwapResult.FinalRenameFailed)
        assertFalse(File(tempFolder.root, "P8080743.JPG").exists())
        assertEquals("original", File(tempFolder.root, "P8080743.JPG.bak").readText())
    }

    @Test
    fun `renameTempIntoPlace called directly reports Verified on a matching-length rename`() {
        val parent = DocumentFile.fromFile(tempFolder.root)
        val temp = tempFolder.newFile("P8080743.JPG.tmp").apply { writeText("tagged!!") }

        val outcome = SafeFileSwap.renameTempIntoPlace(parent, docFor("P8080743.JPG.tmp"), "P8080743.JPG", expectedLength = temp.length())

        assertEquals(RenameTailOutcome.Verified, outcome)
        assertEquals("tagged!!", File(tempFolder.root, "P8080743.JPG").readText())
    }

    @Test
    fun `renameTempIntoPlace reports VerificationFailed when the expected length does not match`() {
        // A real rename that returns true can't be made to "lie" about the resulting
        // file's actual size with genuine file ops — so this deliberately passes a wrong
        // expectedLength rather than trying to fake a corrupted rename.
        val parent = DocumentFile.fromFile(tempFolder.root)
        tempFolder.newFile("P8080743.JPG.tmp").writeText("tagged!!")

        val outcome = SafeFileSwap.renameTempIntoPlace(
            parent, docFor("P8080743.JPG.tmp"), "P8080743.JPG", expectedLength = 999_999L
        )

        assertTrue(outcome is RenameTailOutcome.VerificationFailed)
        // The rename itself still happened — this is a detected-bad-verification case,
        // not a case where nothing moved.
        assertTrue(File(tempFolder.root, "P8080743.JPG").exists())
    }

    @Test
    fun `renameTempIntoPlace reports RenameCallFailed when the temp file does not exist`() {
        val parent = DocumentFile.fromFile(tempFolder.root)

        val outcome = SafeFileSwap.renameTempIntoPlace(parent, docFor("does-not-exist.tmp"), "P8080743.JPG", expectedLength = 0L)

        assertEquals(RenameTailOutcome.RenameCallFailed, outcome)
    }

    @Test
    fun `deleteBackup treats an already-absent backup as successfully gone`() {
        assertTrue(SafeFileSwap.deleteBackup(null))
    }

    @Test
    fun `deleteBackup deletes a real backup file and reports success`() {
        val backup = tempFolder.newFile("P8080743.JPG.bak")

        assertTrue(SafeFileSwap.deleteBackup(DocumentFile.fromFile(backup)))
        assertFalse(backup.exists())
    }
}
