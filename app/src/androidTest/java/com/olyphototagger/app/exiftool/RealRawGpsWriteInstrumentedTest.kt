package com.olyphototagger.app.exiftool

import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Validates the exiftool RAW write path against a real Olympus .ORF file, not a
 * synthetic one — the one thing ExifToolInvokerInstrumentedTest's JPEG test couldn't
 * cover, since AndroidX ExifInterface can build a valid synthetic JPEG but has no write
 * path for RAW at all (the entire reason this app bundles exiftool in the first place).
 *
 * Deliberately does NOT ship a real .ORF as a committed test asset — that would mean
 * committing an actual camera photo into git history, awkward for a repo headed for a
 * public F-Droid release. Instead this looks for a file manually staged on the test
 * device's storage and skips cleanly via Assume when it's not there, so normal test runs
 * (CI, other contributors) are unaffected. To run this for real, push into the app's own
 * external files dir — not /sdcard/Download — since scoped storage blocks the app from
 * reading arbitrary shared-storage paths even when adb push itself succeeds there:
 *
 *   adb push some-real-file.orf \
 *     /sdcard/Android/data/com.olyphototagger.app/files/oly_test_sample.orf
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class RealRawGpsWriteInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val invoker = ExifToolInvoker(context)

    @Test
    fun writesGpsToARealOrfWithoutCorruptingOtherMetadata(): Unit = runBlocking {
        val staged = File(context.getExternalFilesDir(null), "oly_test_sample.orf")
        assumeTrue("No real RAW file staged at ${staged.path} — see class doc to run this", staged.exists())

        invoker.ensureInstalled()

        val work = File(context.cacheDir, "real_raw_test.orf")
        staged.copyTo(work, overwrite = true)

        val before = ExifInterface(work.absolutePath)
        assertNull("Expected the staged sample to start untagged", before.latLong)
        val makeBefore = before.getAttribute(ExifInterface.TAG_MAKE)
        val modelBefore = before.getAttribute(ExifInterface.TAG_MODEL)
        val dateBefore = before.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        assertNotNull("Expected the real file to have a Make tag before any write", makeBefore)

        val args = GpsExifToolCommand.build(
            targetPath = work.absolutePath,
            latitude = 46.4808,
            longitude = 2.2426,
            altitudeMeters = 38.0
        )
        val result = invoker.run(args)
        assertEquals("exiftool RAW GPS write should exit 0, output was: ${result.output}", 0, result.exitCode)

        val after = ExifInterface(work.absolutePath)
        val latLong = after.latLong
        assertNotNull("Expected GPS lat/long to be present after writing to the real RAW file", latLong)
        assertEquals(46.4808, latLong!![0], 0.0001)
        assertEquals(2.2426, latLong[1], 0.0001)

        // The write must not have corrupted the RAW's existing (real, non-GPS) metadata.
        assertEquals(makeBefore, after.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(modelBefore, after.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals(dateBefore, after.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))

        work.delete()
    }
}
