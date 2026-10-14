package io.github.d3sox.musicplayer

import android.app.job.JobParameters
import android.app.job.JobService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import io.github.d3sox.musicplayer.downloads.DownloadStatus
import io.github.d3sox.musicplayer.downloads.DownloadRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample

@OptIn(FlowPreview::class)
class DownloadService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val visibleTransfer = Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob
        if (visibleTransfer) showTransfer(params)
        val app = application as MusicApplication
        val offline = app.offline
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            val progress = if (visibleTransfer) launch {
                offline.state.mapNotNull { records -> records.values.firstOrNull { it.status == DownloadStatus.DOWNLOADING } }
                    .distinctUntilChanged().sample(1_000).collect { record -> showTransfer(params, record) }
            } else null
            try {
                withContext(Dispatchers.IO) {
                    if (params.extras.getBoolean("refresh-playlists")) app.refreshOfflinePlaylists()
                    offline.runPending()
                }
                retry = offline.state.value.values.any { it.status != DownloadStatus.COMPLETE }
            } catch (_: CancellationException) {
                // The transfer retains its partial file and durable request.
            } catch (_: Exception) {
                retry = true
            } finally {
                progress?.cancel()
                if (isActive) {
                    running.remove(params.jobId)
                    jobFinished(params, retry)
                }
            }
        }
        running[params.jobId] = task
        task.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    @androidx.annotation.RequiresApi(34)
    private fun showTransfer(params: JobParameters, record: DownloadRecord? = null) {
        val app = application as MusicApplication
        val title = app.controller.state.value.tracks.firstOrNull { it.id == record?.trackId }?.metadata?.title ?: "Manida"
        val knownSize = record != null && record.sizeBytes > 0
        val percent = if (knownSize) (record.bytesDownloaded.toDouble() / record.sizeBytes * 100).toInt().coerceIn(0, 100) else 0
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("offline-downloads", "Music downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "offline-downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("Downloading music")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (knownSize) 100 else 0, percent, !knownSize)
            .addAction(Notification.Action.Builder(null, "Pause downloads", PendingIntent.getBroadcast(this, 0,
                Intent(this, DownloadActionReceiver::class.java).setAction(DownloadActionReceiver.PAUSE), PendingIntent.FLAG_IMMUTABLE)).build())
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        setNotification(params, 2001, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
    }
}
