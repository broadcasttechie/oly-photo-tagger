package com.olyphototagger.app.write

import com.olyphototagger.app.dcim.CameraFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncompleteWriteDetectorTest {

    private fun file(name: String, folder: String = "100OLYMP") = CameraFile(
        uriString = "content://camera/$folder/$name",
        displayName = name,
        folderName = folder,
        sizeBytes = 1024,
        lastModified = null
    )

    @Test
    fun `an ordinary photo with no tmp or bak sibling is never flagged`() {
        val result = IncompleteWriteDetector.detect(listOf(file("P8080743.JPG"), file("P8080743.ORF")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `original plus a stray temp with no backup is StaleTempOnly`() {
        val result = IncompleteWriteDetector.detect(listOf(file("P8080743.JPG"), file("P8080743.JPG.tmp")))

        val write = result.single()
        assertEquals(IncompleteWriteClassification.StaleTempOnly, write.classification)
        assertEquals("P8080743.JPG", write.original?.displayName)
        assertEquals("P8080743.JPG.tmp", write.temp?.displayName)
        assertEquals(null, write.backup)
    }

    @Test
    fun `temp plus backup with no original is AwaitingChoice`() {
        val result = IncompleteWriteDetector.detect(listOf(file("P8080743.JPG.tmp"), file("P8080743.JPG.bak")))

        val write = result.single()
        assertEquals(IncompleteWriteClassification.AwaitingChoice, write.classification)
        assertEquals(null, write.original)
        assertEquals("P8080743.JPG.tmp", write.temp?.displayName)
        assertEquals("P8080743.JPG.bak", write.backup?.displayName)
    }

    @Test
    fun `only a backup is BackupOnly`() {
        val result = IncompleteWriteDetector.detect(listOf(file("P8080743.JPG.bak")))

        val write = result.single()
        assertEquals(IncompleteWriteClassification.BackupOnly, write.classification)
        assertEquals(null, write.original)
        assertEquals(null, write.temp)
        assertEquals("P8080743.JPG.bak", write.backup?.displayName)
    }

    @Test
    fun `only a temp with no original or backup is TempOnly`() {
        val result = IncompleteWriteDetector.detect(listOf(file("P8080743.JPG.tmp")))

        val write = result.single()
        assertEquals(IncompleteWriteClassification.TempOnly, write.classification)
        assertEquals(null, write.original)
        assertEquals("P8080743.JPG.tmp", write.temp?.displayName)
        assertEquals(null, write.backup)
    }

    @Test
    fun `original plus backup with no temp is OriginalAndBackupPresent`() {
        val result = IncompleteWriteDetector.detect(listOf(file("P8080743.JPG"), file("P8080743.JPG.bak")))

        val write = result.single()
        assertEquals(IncompleteWriteClassification.OriginalAndBackupPresent, write.classification)
        assertEquals("P8080743.JPG", write.original?.displayName)
        assertEquals(null, write.temp)
        assertEquals("P8080743.JPG.bak", write.backup?.displayName)
    }

    @Test
    fun `original plus temp plus backup all present is AllThreePresent`() {
        val result = IncompleteWriteDetector.detect(
            listOf(file("P8080743.JPG"), file("P8080743.JPG.tmp"), file("P8080743.JPG.bak"))
        )

        val write = result.single()
        assertEquals(IncompleteWriteClassification.AllThreePresent, write.classification)
    }

    @Test
    fun `recovered name strips only the tmp or bak suffix, not the real extension`() {
        val result = IncompleteWriteDetector.detect(listOf(file("P8080743.JPG.tmp"), file("P8080743.JPG.bak")))

        // CameraFile.baseName only strips the last dot — a naive uniform baseName
        // comparison would incorrectly try to match "P8080743" (the JPEG's own baseName)
        // rather than "P8080743.JPG" (the orphan's recovered name), and never group them.
        assertEquals("P8080743.JPG", result.single().recoveredName)
    }

    @Test
    fun `grouping is case-insensitive on the recovered name`() {
        val result = IncompleteWriteDetector.detect(listOf(file("p8080743.jpg"), file("P8080743.JPG.tmp")))

        assertEquals(1, result.size)
        assertEquals(IncompleteWriteClassification.StaleTempOnly, result.single().classification)
    }

    @Test
    fun `same base name in different folders is never grouped together`() {
        val result = IncompleteWriteDetector.detect(
            listOf(file("P8080743.JPG", folder = "100OLYMP"), file("P8080743.JPG.tmp", folder = "101OLYMP"))
        )

        // The 100OLYMP original, on its own, isn't a recovery case at all. The 101OLYMP
        // temp genuinely has no original *in that folder* — correctly reported as an
        // unexplained TempOnly orphan, not silently matched against the other folder's
        // unrelated original of the same name.
        val write = result.single()
        assertEquals("101OLYMP", write.folderName)
        assertEquals(IncompleteWriteClassification.TempOnly, write.classification)
    }

    @Test
    fun `multiple independent orphan groups in one scan are all detected separately`() {
        val result = IncompleteWriteDetector.detect(
            listOf(
                file("P8080743.JPG"), file("P8080743.JPG.tmp"), // StaleTempOnly
                file("P8080744.JPG.tmp"), file("P8080744.JPG.bak"), // AwaitingChoice
                file("P8080745.ORF.bak"), // BackupOnly
                file("P8080746.JPG"), file("P8080746.ORF") // ordinary pair, not flagged
            )
        )

        assertEquals(3, result.size)
        val byRecoveredName = result.associateBy { it.recoveredName }
        assertEquals(IncompleteWriteClassification.StaleTempOnly, byRecoveredName.getValue("P8080743.JPG").classification)
        assertEquals(IncompleteWriteClassification.AwaitingChoice, byRecoveredName.getValue("P8080744.JPG").classification)
        assertEquals(IncompleteWriteClassification.BackupOnly, byRecoveredName.getValue("P8080745.ORF").classification)
    }

    @Test
    fun `GPSLOG and other unrelated files never trigger detection`() {
        val result = IncompleteWriteDetector.detect(listOf(file("GPSLOG0001.LOG"), file("VIDEO0001.MOV")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a real SAF-mutated temp name is still recognized as AwaitingChoice, not missed as BackupOnly`() {
        // Direct regression test for a real bug found via genuine on-device crash
        // simulation (2026-08-11): a real SAF-backed DocumentFile.createFile() renamed the
        // requested "P8080743.JPG.tmp" to "P8080743.JPG.tmp.jpg" on disk (appending its own
        // recognized extension for the image/jpeg mimeType). Before the parseArtifactName
        // fix, this file's last extension was "JPG", not "TMP", so it fell through to
        // Role.ORIGINAL, formed its own single-file group that classify() correctly
        // discards as "not a recovery case", and vanished — leaving the detector unable to
        // see it at all. The scan then reported this case as BackupOnly (only the .bak
        // visible), silently losing the ability to offer "Finish tagging" and permanently
        // orphaning the already-verified tagged temp file. It must classify exactly the
        // same as the idealized "P8080743.JPG.tmp" form.
        val result = IncompleteWriteDetector.detect(
            listOf(file("P8080743.JPG.tmp.jpg"), file("P8080743.JPG.bak"))
        )

        val write = result.single()
        assertEquals(IncompleteWriteClassification.AwaitingChoice, write.classification)
        assertEquals("P8080743.JPG", write.recoveredName)
        assertEquals(null, write.original)
        assertEquals("P8080743.JPG.tmp.jpg", write.temp?.displayName)
        assertEquals("P8080743.JPG.bak", write.backup?.displayName)
    }
}
