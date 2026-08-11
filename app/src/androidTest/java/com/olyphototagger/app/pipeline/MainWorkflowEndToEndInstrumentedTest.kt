package com.olyphototagger.app.pipeline

import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.olyphototagger.app.cache.AppDatabase
import com.olyphototagger.app.dawarich.DawarichClient
import com.olyphototagger.app.dawarich.createDawarichHttpClient
import com.olyphototagger.app.dcim.DcimScanner
import com.olyphototagger.app.exif.PhotoExifStatusReader
import com.olyphototagger.app.exiftool.ExifToolInvoker
import com.olyphototagger.app.geotag.GeoInterpolator
import com.olyphototagger.app.geotag.GeoMatch
import com.olyphototagger.app.write.GpsExifWriteResult
import com.olyphototagger.app.write.GpsExifWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.ZoneOffset

/**
 * Runs the real GeotagOrchestrator end to end: scan real sample photos -> match against a
 * real Dawarich track -> write GPS to both RAW and JPEG. Nothing here is mocked.
 *
 * Requires real Dawarich credentials and a real Dawarich instance with track data
 * covering the sample photos' capture window — deliberately not committed anywhere, so
 * this needs to be run manually with instrumentation arguments:
 *
 *   adb shell am instrument -w \
 *     -e class com.olyphototagger.app.pipeline.MainWorkflowEndToEndInstrumentedTest \
 *     -e dawarichBaseUrl <url> -e dawarichToken <token> \
 *     [-e assumedOffsetHours <int, default 1>] \
 *     com.olyphototagger.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Also requires sample JPG/ORF photos staged at externalFilesDir/DCIM_test/100OLYMP/ —
 * see RealRawGpsWriteInstrumentedTest's class doc for why real photos aren't a committed
 * test asset.
 */
@RunWith(AndroidJUnit4::class)
class MainWorkflowEndToEndInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun scansMatchesAndWritesRealSamplePhotosAgainstRealDawarichTrack(): Unit = runBlocking {
        val baseUrl = args.getString("dawarichBaseUrl")
        val token = args.getString("dawarichToken")
        assumeTrue(
            "Pass -e dawarichBaseUrl <url> -e dawarichToken <token> to run this — see class doc",
            baseUrl != null && token != null
        )

        val dcimParent = File(context.getExternalFilesDir(null), "DCIM_test")
        val dcimRoot = File(dcimParent, "100OLYMP")
        // Created here (not by `adb shell mkdir`) deliberately: a directory an app didn't
        // create itself under its external files dir can end up owned by the shell UID
        // instead of the app's, which the app's own storage sandbox then can't see even
        // via matching group permissions — confirmed by hand while building this test.
        dcimRoot.mkdirs()
        assumeTrue("No sample photos staged at ${dcimRoot.path} — see class doc", dcimRoot.listFiles()?.isNotEmpty() == true)

        val offsetHours = args.getString("assumedOffsetHours")?.toLongOrNull() ?: 1L
        val offset = ZoneOffset.ofHours(offsetHours.toInt())
        Log.i(TAG, "Using assumed camera clock offset: $offset")

        val orchestrator = GeotagOrchestrator(
            dcimScanner = DcimScanner(context.contentResolver),
            exifStatusReader = PhotoExifStatusReader(context.contentResolver),
            geoTagCacheDao = AppDatabase.getInstance(context).geoTagCacheDao(),
            gpsSource = DawarichClient(createDawarichHttpClient(), baseUrl!!, token!!),
            geoInterpolator = GeoInterpolator(maxBracketGap = Duration.ofMinutes(30)),
            gpsExifWriter = GpsExifWriter(context.contentResolver, ExifToolInvoker(context), context.cacheDir),
            writeLogDao = AppDatabase.getInstance(context).writeLogDao()
        )

        val scan = orchestrator.scanForMatches(DocumentFile.fromFile(dcimParent), offset)

        Log.i(TAG, "matches=${scan.matches.size} excluded=${scan.excluded.size} ignored=${scan.ignoredFiles.size} conflicts=${scan.conflicts.size}")
        scan.excluded.forEach { Log.w(TAG, "EXCLUDED ${it.pair.baseName}: ${it.reason}") }
        scan.matches.forEach { match ->
            Log.i(TAG, "MATCH ${match.pair.baseName} @ ${match.timestamp}: ${match.geoMatch}")
        }

        assertEquals(
            "Expected all 5 sample pairs to be found by the scanner/pairer",
            5,
            scan.matches.size + scan.excluded.size
        )
        assertTrue(
            "No matches at all — wrong assumed offset, or no Dawarich track data for this window: ${scan.excluded}",
            scan.matches.isNotEmpty()
        )

        val writeResults = scan.matches.map { match ->
            val geo = match.geoMatch
            assertTrue("${match.pair.baseName} did not resolve to a Matched position: $geo", geo is GeoMatch.Matched)
            orchestrator.applyMatch(scan, match)
        }

        writeResults.forEach { result ->
            Log.i(TAG, "WRITE ${result.pair.baseName}: jpeg=${result.jpegResult} raw=${result.rawResult}")
            assertTrue(
                "${result.pair.baseName} JPEG write did not succeed: ${result.jpegResult}",
                result.jpegResult is GpsExifWriteResult.Written
            )
            assertTrue(
                "${result.pair.baseName} RAW write did not succeed: ${result.rawResult}",
                result.rawResult is GpsExifWriteResult.Written
            )
        }
    }

    companion object {
        private const val TAG = "E2ETest"
    }
}
