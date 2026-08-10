package com.olyphototagger.app.write

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.olyphototagger.app.dcim.DcimScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Confirms the full real chain — DcimScanner's actual SAF-style folder walk, feeding
 * IncompleteWriteDetector's classification — finds a staged `.tmp`+`.bak` pair. No real
 * photo needed: synthetic files with the right names and a stray "original" JPEG are
 * enough to exercise the detection path end to end.
 */
@RunWith(AndroidJUnit4::class)
class IncompleteWriteScannerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun scanFindsAndClassifiesARealStagedIncompleteWrite(): Unit = runBlocking {
        val dcimRoot = File(context.getExternalFilesDir(null), "incomplete_write_scan_test")
        dcimRoot.deleteRecursively()
        val folder = File(dcimRoot, "100OLYMP").apply { mkdirs() }

        // The core "mid-flight" case: temp + backup, no original under its real name.
        File(folder, "P8080743.JPG.tmp").writeText("tagged")
        File(folder, "P8080743.JPG.bak").writeText("original")
        // An ordinary, unrelated pair that must NOT be flagged.
        File(folder, "P8080744.JPG").writeText("untouched jpeg")
        File(folder, "P8080744.ORF").writeText("untouched raw")

        val scanner = IncompleteWriteScanner(DcimScanner())
        val result = scanner.scan(DocumentFile.fromFile(dcimRoot))

        val item = result.items.single()
        assertEquals("P8080743.JPG", item.recoveredName)
        assertEquals(IncompleteWriteClassification.AwaitingChoice, item.classification)
        assertEquals("100OLYMP", item.folderName)

        // Confirm resolve() round-trips: complete tagging via the scanner's own dispatch,
        // then confirm the real file on disk ended up correct.
        val actionResult = scanner.resolve(result, item, RecoveryChoice.COMPLETE_TAGGING)
        assertEquals(RecoveryActionResult.Recovered, actionResult)
        assertEquals("tagged", File(folder, "P8080743.JPG").readText())
    }
}
