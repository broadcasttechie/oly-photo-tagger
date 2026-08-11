package com.olyphototagger.app.exiftool

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            latitude = 46.4808,
            longitude = 2.2426,
            altitudeMeters = 38.0
        )
        val result = invoker.run(args)
        assertEquals("exiftool GPS write should exit 0, output was: ${result.output}", 0, result.exitCode)

        val latLong = ExifInterface(jpeg.absolutePath).latLong
        assertTrue("Expected GPS lat/long to be present after write", latLong != null)
        assertEquals(46.4808, latLong!![0], 0.0001)
        assertEquals(2.2426, latLong[1], 0.0001)

        jpeg.delete()
    }

    /**
     * Regression test for a real race condition: [AssetExtractor.ensureInstalled] used to
     * have no locking around its check-then-act "am I installed? if not, delete and
     * re-extract" logic. That's invisible on a warm install (every call short-circuits on
     * a pure read), so it only ever mattered the first time anything called it after a
     * fresh install — which nothing did concurrently until GeotagOrchestrator started
     * resolving/writing files in parallel. Forces that exact fresh-install race window,
     * fires several concurrent calls into it, and confirms the result is still a genuinely
     * working install — not just a marker file claiming so.
     */
    @Test
    fun concurrentEnsureInstalledCallsOnAFreshInstallDoNotCorruptTheExtraction(): Unit = runBlocking {
        AssetExtractor.perl5Dir(context).deleteRecursively()
        File(context.filesDir, "perl5.version").delete()
        File(context.filesDir, "xs_linked.dir").delete()
        assertFalse("Test setup should have produced a genuinely fresh, not-installed state", invoker.isInstalled())

        coroutineScope {
            repeat(8) {
                launch { invoker.ensureInstalled() }
            }
        }

        assertTrue(
            "Expected a fully installed, uncorrupted extraction after concurrent calls raced for it",
            invoker.isInstalled()
        )
        val result = invoker.run(listOf("-ver"))
        assertEquals(
            "exiftool -ver should still succeed against the concurrently-installed runtime, output was: ${result.output}",
            0,
            result.exitCode
        )
    }

    private fun perlBinaryPath() = "${context.applicationInfo.nativeLibraryDir}/libperl.so"
}
