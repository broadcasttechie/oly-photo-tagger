package com.olyphototagger.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.olyphototagger.app.cache.AppDatabase
import com.olyphototagger.app.pipeline.buildGeotagOrchestrator
import com.olyphototagger.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneOffset

/**
 * adb-triggerable equivalents of a couple of Home-screen actions, so verifying scan/cache
 * behavior against a real, currently-connected card doesn't need a manual find-the-button-
 * and-tap-it cycle (screenshot, locate coordinates, tap, wait, screenshot again) every single
 * time. Lives entirely under src/debug — not a runtime `if (BuildConfig.DEBUG)` guard — so
 * it, and its manifest entry, are structurally absent from a release build rather than merely
 * dormant in one.
 *
 * Drives the exact same [buildGeotagOrchestrator] wiring and persisted Settings values the
 * real UI uses (see [com.olyphototagger.app.ui.workflow.GeotagWorkflowViewModel]'s own
 * `runPreScan`/init) — nothing here is faked or shortcut, so a count or timing logged here is
 * exactly what the real button would have produced.
 *
 * Usage:
 * ```
 * adb shell am broadcast -a com.olyphototagger.app.debug.SCAN \
 *     -n com.olyphototagger.app/com.olyphototagger.app.debug.DebugControlReceiver
 * adb shell am broadcast -a com.olyphototagger.app.debug.CLEAR_CACHE \
 *     -n com.olyphototagger.app/com.olyphototagger.app.debug.DebugControlReceiver
 * adb logcat -s DebugControl:*
 * ```
 */
class DebugControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        // Deliberately NOT goAsync() — confirmed for real (2026-09-09) that it does not
        // buy the unbounded time a full-card SCAN needs: a genuinely cold scan against a
        // real 1000+ photo card took 166s, and the system raised a real "app isn't
        // responding" dialog anyway well before that, goAsync() notwithstanding — it's
        // meant for a short grace period past an instant return, not minutes. debugScope is
        // a plain, receiver-independent coroutine scope that already lives for the process's
        // whole lifetime (same shape as WriteService's own serviceScope), so onReceive()
        // itself now does nothing slow — it just schedules work and returns immediately —
        // and the system has nothing to time out regardless of how long that work runs.
        debugScope.launch {
            try {
                when (action) {
                    ACTION_SCAN -> runScan(appContext)
                    ACTION_CLEAR_CACHE -> clearCache(appContext)
                    else -> Log.w(TAG, "Unknown action: $action")
                }
            } catch (e: Exception) {
                Log.e(TAG, "$action failed", e)
            }
        }
    }

    /** The adb equivalent of tapping "Check for Untagged Photos" — [preScan] rather than
     *  [scanForMatches][com.olyphototagger.app.pipeline.GeotagOrchestrator.scanForMatches]
     *  specifically, since it's the lighter no-network path that still exercises exactly the
     *  per-file cache logic being measured, without also depending on the GPS source (e.g.
     *  Dawarich) being reachable. */
    private suspend fun runScan(context: Context) {
        val settingsRepository = SettingsRepository(context)
        val rootString = settingsRepository.lastDcimRootUri.first()
        if (rootString == null) {
            Log.w(TAG, "SCAN: no folder selected yet — pick one via the app first.")
            return
        }
        val root = Uri.parse(rootString)
        val stillGranted = context.contentResolver.persistedUriPermissions.any { it.uri == root && it.isReadPermission }
        if (!stillGranted) {
            Log.w(TAG, "SCAN: persisted permission for $root is gone — re-pick the folder via the app.")
            return
        }
        val dcimRoot = DocumentFile.fromTreeUri(context, root)
        if (dcimRoot == null) {
            Log.w(TAG, "SCAN: could not open $root")
            return
        }
        val orchestrator = buildGeotagOrchestrator(context)
        if (orchestrator == null) {
            Log.w(TAG, "SCAN: no GPS source configured — set one up via the app first.")
            return
        }

        val offsetSeconds = settingsRepository.lastCameraOffsetSeconds.first() ?: 0
        val startedAtMs = System.currentTimeMillis()
        val summary = orchestrator.preScan(dcimRoot, ZoneOffset.ofTotalSeconds(offsetSeconds))
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        Log.i(
            TAG,
            "SCAN done in ${elapsedMs}ms — needsTagging=${summary.needsTagging} " +
                "alreadyTagged=${summary.alreadyTagged} noTimestamp=${summary.noTimestamp} " +
                "outsideDateRange=${summary.outsideDateRange} ignoredFiles=${summary.ignoredFiles} " +
                "conflicts=${summary.conflicts} cacheHits=${summary.cacheHits} cacheMisses=${summary.cacheMisses}"
        )
    }

    /** The adb equivalent of an uninstall's data wipe, but only for the geotag cache — lets a
     *  cold-cache timing be re-measured against the same card without losing the SAF folder
     *  grant (an uninstall would) or anything else in Settings. */
    private suspend fun clearCache(context: Context) {
        AppDatabase.getInstance(context).geoTagCacheDao().clear()
        Log.i(TAG, "CLEAR_CACHE done — geotag_cache emptied")
    }

    companion object {
        private const val TAG = "DebugControl"
        private const val ACTION_SCAN = "com.olyphototagger.app.debug.SCAN"
        private const val ACTION_CLEAR_CACHE = "com.olyphototagger.app.debug.CLEAR_CACHE"

        // See onReceive()'s doc for why this exists instead of goAsync(). SupervisorJob so
        // one failed action can never cancel a later, unrelated one sharing this scope.
        private val debugScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
