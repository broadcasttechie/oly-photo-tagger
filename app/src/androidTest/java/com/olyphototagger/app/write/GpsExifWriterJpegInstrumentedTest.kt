package com.olyphototagger.app.write

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.olyphototagger.app.exiftool.ExifToolInvoker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Confirms the real [GpsExifWriter.write] path — SAF temp-file staging, exiftool
 * invocation, verify/delete/rename — for JPEG specifically, now that it goes through
 * exiftool instead of AndroidX ExifInterface (see GpsExifWriter's class doc for why).
 * `DocumentFile.fromFile` wraps a plain filesystem File as a DocumentFile without needing
 * a real SAF tree, so this needs no picker interaction — just a staged sample.
 *
 * Stage a real untagged camera JPEG at getExternalFilesDir(null)/oly_test_sample.jpg to
 * run this for real; skips cleanly via Assume otherwise.
 */
@RunWith(AndroidJUnit4::class)
class GpsExifWriterJpegInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun writesGpsToARealJpegViaExifTool(): Unit = runBlocking {
        val staged = File(context.getExternalFilesDir(null), "oly_test_sample.jpg")
        assumeTrue("No real JPEG staged at ${staged.path} — see class doc to run this", staged.exists())

        val workDir = File(context.getExternalFilesDir(null), "jpeg_write_test")
        workDir.deleteRecursively()
        workDir.mkdirs()
        val work = File(workDir, "P_test.JPG")
        staged.copyTo(work, overwrite = true)

        val writer = GpsExifWriter(
            context.contentResolver,
            ExifToolInvoker(context),
            context.cacheDir
        )

        // DocumentFile.fromFile(work) directly would construct it with parent = null (it's
        // meant as a tree root, not a child) — GpsExifWriter needs a real parentFile to
        // create its sibling .tmp file, same as any real SAF tree DocumentFile has. Going
        // through the parent dir's listFiles() gives a properly-parented DocumentFile.
        val workDirDoc = DocumentFile.fromFile(workDir)
        val original = requireNotNull(workDirDoc.listFiles().find { it.name == work.name }) {
            "Could not find ${work.name} via DocumentFile.listFiles()"
        }

        val result = writer.write(
            original = original,
            latitude = 53.4808,
            longitude = -2.2426,
            altitudeMeters = 38.0
        )

        assertTrue("Expected Written, got $result", result is GpsExifWriteResult.Written)
        assertTrue("Expected the file to still exist under its original name after rename", work.exists())
        assertEquals("Expected exactly one file in the work dir (no stray .tmp)", 1, workDir.listFiles()?.size)
    }
}
