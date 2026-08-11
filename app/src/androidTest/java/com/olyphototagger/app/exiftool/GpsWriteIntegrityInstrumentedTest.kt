package com.olyphototagger.app.exiftool

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a pass/fail correctness test on its own — it runs the real production write path
 * (ExifToolInvoker + GpsExifToolCommand, the exact code that ships) against every staged
 * sample file and saves a before/after copy of each so a much more thorough integrity
 * check (full metadata tag diff, pixel/preview-data hashing) can be done externally with
 * exiftool and stdlib tooling — that kind of deep binary comparison isn't practical to
 * write as Kotlin/JUnit assertions. See MainWorkflowEndToEndInstrumentedTest's class doc
 * for how to stage sample files; results land in
 * getExternalFilesDir(null)/integrity_results/, pulled off the device afterward via
 * `adb pull`.
 */
@RunWith(AndroidJUnit4::class)
class GpsWriteIntegrityInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val invoker = ExifToolInvoker(context)

    @Test
    fun writesGpsToAllStagedSamplesAndSavesBeforeAfterForExternalVerification(): Unit = runBlocking {
        val sampleDir = File(context.getExternalFilesDir(null), "DCIM_test/100OLYMP")
        assumeTrue("No sample photos staged at ${sampleDir.path}", sampleDir.listFiles()?.isNotEmpty() == true)

        invoker.ensureInstalled()

        val resultsDir = File(context.getExternalFilesDir(null), "integrity_results")
        resultsDir.deleteRecursively()
        resultsDir.mkdirs()

        val samples = sampleDir.listFiles { f -> f.extension.uppercase() in setOf("JPG", "ORF") }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("Expected sample files under ${sampleDir.path}", samples.isNotEmpty())

        for (sample in samples) {
            val before = File(resultsDir, "${sample.name}.before")
            sample.copyTo(before, overwrite = true)

            val after = File(resultsDir, "${sample.name}.after")
            sample.copyTo(after, overwrite = true)

            val args = GpsExifToolCommand.build(
                targetPath = after.absolutePath,
                latitude = 46.4808,
                longitude = 2.2426,
                altitudeMeters = 38.0
            )
            val result = invoker.run(args)
            assertEquals("${sample.name}: exiftool write should exit 0: ${result.output}", 0, result.exitCode)
        }
    }
}
