package com.olyphototagger.app.exiftool

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the bundled perl+exiftool on a real device/emulator — nothing about whether the
 * cross-compiled binary actually executes can be verified by a JVM unit test or a
 * successful Gradle build; this is the first real check of that.
 */
@RunWith(AndroidJUnit4::class)
class ExifToolInvokerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val invoker = ExifToolInvoker(context)

    @Test
    fun assetExtractionAndExiftoolVersionCheck(): Unit = runBlocking {
        assertTrue("libperl.so should be packaged in this APK", File(perlBinaryPath()).exists())

        invoker.ensureInstalled()
        assertTrue("AssetExtractor should report installed after ensureInstalled()", invoker.isInstalled())

        val result = invoker.run(listOf("-ver"))
        assertEquals("exiftool -ver should exit 0, output was: ${result.output}", 0, result.exitCode)
        assertTrue(
            "exiftool -ver output should look like a version number, was: ${result.output}",
            result.output.trim().matches(Regex("""\d+\.\d+"""))
        )
    }

    @Test
    fun writesGpsToARealJpegAndReadsItBack(): Unit = runBlocking {
        invoker.ensureInstalled()

        val jpeg = File(context.cacheDir, "exiftool_instrumented_test.jpg")
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
            jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }

        val args = GpsExifToolCommand.build(
            targetPath = jpeg.absolutePath,
            latitude = 53.4808,
            longitude = -2.2426,
            altitudeMeters = 38.0
        )
        val result = invoker.run(args)
        assertEquals("exiftool GPS write should exit 0, output was: ${result.output}", 0, result.exitCode)

        val latLong = ExifInterface(jpeg.absolutePath).latLong
        assertTrue("Expected GPS lat/long to be present after write", latLong != null)
        assertEquals(53.4808, latLong!![0], 0.0001)
        assertEquals(-2.2426, latLong[1], 0.0001)

        jpeg.delete()
    }

    private fun perlBinaryPath() = "${context.applicationInfo.nativeLibraryDir}/libperl.so"
}
