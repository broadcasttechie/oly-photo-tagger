package com.olyphototagger.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.olyphototagger.app.MainActivity
import com.olyphototagger.app.R
import com.olyphototagger.app.pipeline.PairWriteResult
import com.olyphototagger.app.pipeline.ProposedMatch
import com.olyphototagger.app.pipeline.ScanResult
import com.olyphototagger.app.pipeline.buildGeotagOrchestrator
import com.olyphototagger.app.ui.workflow.RunProgress
import com.olyphototagger.app.write.GpsExifWriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Runs the GPS-tag write batch as a foreground service, so switching to another app no longer
 * risks the OS reclaiming the process mid-batch — the one gap [GeotagWorkflowViewModel]'s own
 * `viewModelScope` couldn't close (it already survives in-app navigation; it never survived
 * being merely backgrounded). This is defense-in-depth, not a new safety dependency: every
 * individual write is already safe to interrupt and recoverable via the Recovery screen even
 * if this service's process is killed anyway.
 *
 * Started, never bound ([onBind] returns null) — the (non-Parcelable) [ScanResult]/
 * [ProposedMatch] payload and live progress cross the ViewModel<->Service boundary via this
 * class's own companion object instead, valid only because both live in the same process (this
 * service must never declare android:process in the manifest). [status] is a process-wide
 * [StateFlow], so a freshly-constructed ViewModel (e.g. after the Activity was recreated while
 * this service kept running) picks up right where an in-flight or just-finished batch left off
 * for free — a [StateFlow] always replays its latest value to a new collector.
 */
class WriteService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var writeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defense-in-depth behind GeotagWorkflowViewModel.startRun()'s own
        // "if (runProgress != null) return" guard, which already makes a real double-start
        // from the UI hard to trigger. A redundant onStartCommand (a batch is already running)
        // just discards whatever request it raced in with — the batch already in flight is
        // left undisturbed, never queued or errored.
        if (writeJob?.isActive == true) {
            pendingRequest = null
            return START_NOT_STICKY
        }
        val request = pendingRequest ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        pendingRequest = null // consumed once; a later unrelated restart must never replay it

        val initialProgress = RunProgress(0, request.matches.size, "Starting…", request.startedAt)
        // Must happen before any suspension point, per the foreground-service contract.
        //
        // Calls the platform Service.startForeground(id, notification, type) directly rather
        // than the "recommended" androidx.core.app.ServiceCompat.startForeground() — confirmed
        // on-device (2026-09-09, real emulator, API 35) that ServiceCompat 1.15.0 throws
        // InvalidForegroundServiceTypeException("Starting FGS with type none") for this exact
        // type/manifest/permission combination, even though the compiled type constant and the
        // PackageManager-reported manifest type both verify as identical (8192/mediaProcessing)
        // right before the call — isolated by bypassing ServiceCompat with the same arguments,
        // which succeeds every time. A real bug in that AndroidX version on this API level, not
        // anything wrong with this app's manifest/permissions/call site. minSdk 26 is below the
        // API 29 3-arg startForeground(id, notification, type) overload, hence the branch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildProgressNotification(initialProgress),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(NOTIFICATION_ID, buildProgressNotification(initialProgress))
        }
        _status.value = Status.Running(initialProgress)

        writeJob = serviceScope.launch { runBatch(request) }
        return START_NOT_STICKY
    }

    private suspend fun runBatch(request: Request) {
        var results: List<PairWriteResult> = emptyList()
        try {
            // Pathological race only — GeotagWorkflowViewModel.startRun() already validated
            // this pre-flight before ever calling start(); returning here just means a
            // "0 of 0" finished summary rather than the impossible "no orchestrator to run."
            val orchestrator = buildGeotagOrchestrator(applicationContext) ?: return
            results = orchestrator.applyMatches(request.scanResult, request.matches) { result, completed, total ->
                val progress = RunProgress(completed, total, "Wrote ${result.pair.baseName}", request.startedAt, result.pair)
                _status.value = Status.Running(progress)
                notify(buildProgressNotification(progress))
            }
        } finally {
            val duration = Duration.between(request.startedAt, Instant.now())
            _status.value = Status.Finished(results, duration)
            notify(buildFinalNotification(results, duration))
            // Direct platform call, not ServiceCompat — see the startForeground() call's own
            // doc above. stopForeground(int) needs no version branching regardless (added in
            // API 24, below this app's minSdk 26).
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    /** API 35+: called if a mediaProcessing service hits its 6h/24h runtime budget — far more
     *  than any realistic batch at the ~8.4s/pair this app measures in real use, but handled
     *  so a pathological run stops cleanly rather than being killed out from under itself. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        writeJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Writing GPS tags",
            NotificationManager.IMPORTANCE_LOW // progress, not an alert — no sound/heads-up
        ).apply { description = "Shows progress while GPS tags are being written to photos" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_write)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun buildProgressNotification(progress: RunProgress): Notification =
        baseNotification()
            .setContentTitle("Writing GPS tags")
            .setContentText("${progress.completed} of ${progress.total} — ${progress.currentAction}")
            .setProgress(progress.total, progress.completed, false)
            .setOngoing(true)
            .build()

    private fun buildFinalNotification(results: List<PairWriteResult>, duration: Duration): Notification {
        val succeeded = results.count { result ->
            listOfNotNull(result.jpegResult, result.rawResult).all { it is GpsExifWriteResult.Written }
        }
        return baseNotification()
            .setContentTitle("Writing GPS tags finished")
            .setContentText("$succeeded of ${results.size} photo pairs tagged successfully")
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    /** A no-op, not a crash, when POST_NOTIFICATIONS was never granted — this service's actual
     *  job (surviving backgrounding) never depends on the notification being visible, only on
     *  [Service.startForeground] having been called at all. */
    private fun notify(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    /** What a running or just-finished batch looks like, process-wide — see the class doc
     *  for why a companion [StateFlow] (rather than binding) is this service's public API.
     *  A direct nested type, not declared inside [Companion] — that's what makes `WriteService
     *  .Status` resolve from other files, rather than requiring `WriteService.Companion.Status`. */
    sealed interface Status {
        data object Idle : Status
        data class Running(val progress: RunProgress) : Status
        data class Finished(val results: List<PairWriteResult>, val duration: Duration) : Status
    }

    private data class Request(
        val scanResult: ScanResult,
        val matches: List<ProposedMatch>,
        val startedAt: Instant
    )

    companion object {
        private const val CHANNEL_ID = "write_progress"
        private const val NOTIFICATION_ID = 1001

        private val _status = MutableStateFlow<Status>(Status.Idle)
        val status: StateFlow<Status> = _status.asStateFlow()

        // Same-process handoff for data too large/complex for Intent extras (ScanResult
        // carries a DocumentFile-resolving closure — never Parcelable). Set immediately before
        // starting the service, consumed exactly once by onStartCommand.
        @Volatile private var pendingRequest: Request? = null

        fun start(context: Context, scanResult: ScanResult, matches: List<ProposedMatch>, startedAt: Instant) {
            pendingRequest = Request(scanResult, matches, startedAt)
            ContextCompat.startForegroundService(context, Intent(context, WriteService::class.java))
        }

        /** Called once the app has shown a [Status.Finished] batch to the user (see
         *  GeotagWorkflowViewModel.resetForNextRun()) — without this, a StateFlow replaying
         *  its latest value would hand a much-later, unrelated fresh ViewModel stale results. */
        fun resetIfFinished() {
            _status.update { current -> if (current is Status.Finished) Status.Idle else current }
        }
    }
}
