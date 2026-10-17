package me.d3sox.manida

import android.app.job.JobParameters
import android.app.job.JobService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import io.github.d3sox.musicplayer.downloads.DownloadStatus
import io.github.d3sox.musicplayer.app.AppState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample

@OptIn(FlowPreview::class)
class DownloadService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Pair<Int, String?>, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val key = requestKey(params)
        val visibleTransfer = Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob
        if (visibleTransfer) showTransfer(params)
        val app = application as MusicApplication
        val offline = app.offline
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            val progress = if (visibleTransfer) launch {
                app.controller.state.map { transferProgress(it) }.distinctUntilChanged().sample(1_000)
                    .collect { progress -> showTransfer(params, progress) }
            } else null
            try {
                withContext(Dispatchers.IO) {
                    if (params.extras.getBoolean("refresh-playlists")) app.refreshOfflinePlaylists()
                    app.controller.linkSameFiles()
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
                    running.remove(key)
                    jobFinished(params, retry)
                }
            }
        }
        running[key] = task
        task.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(requestKey(params))?.cancel()
        return true
    }

    // A replacement can start before Android delivers the previous request's stop callback.
    private fun requestKey(params: JobParameters) = params.jobId to params.extras.getString(DOWNLOAD_SCHEDULE_ID)

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private data class TransferProgress(val title: String = "Manida", val text: String = "Downloading music", val percent: Int? = null)

    // Batches of several songs report how many are done; a single song reports its transferred bytes.
    private fun transferProgress(state: AppState): TransferProgress {
        val record = state.downloads.values.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
        val song = state.tracks.firstOrNull { it.id == record?.trackId }?.metadata?.title ?: "Manida"
        val batch = state.downloadBatch
        if (batch.total > 1) {
            val metadata = state.tracks.filter { it.id in batch.trackIds }.map { it.metadata }
            // Name the playlist, album, or artist when the whole batch comes from one.
            val title = state.playlistDownloads.firstOrNull { it.playlist.trackIds.containsAll(batch.trackIds) }?.playlist?.name
                ?: metadata.map { it.album }.distinct().singleOrNull()?.takeIf { it.isNotBlank() }
                ?: metadata.map { it.artist }.distinct().singleOrNull()?.takeIf { it.isNotBlank() }
                ?: song
            return TransferProgress(title, "Downloading · ${batch.downloaded} of ${batch.total} songs",
                (batch.fraction * 100).toInt().coerceIn(0, 100))
        }
        if (record == null) return TransferProgress()
        val percent = if (record.sizeBytes > 0) (record.bytesDownloaded.toDouble() / record.sizeBytes * 100).toInt().coerceIn(0, 100) else null
        return TransferProgress(song, percent = percent)
    }

    @androidx.annotation.RequiresApi(34)
    private fun showTransfer(params: JobParameters, progress: TransferProgress = TransferProgress()) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("offline-downloads", "Music downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "offline-downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(progress.title)
            .setContentText(progress.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (progress.percent != null) 100 else 0, progress.percent ?: 0, progress.percent == null)
            .addAction(Notification.Action.Builder(null, "Pause downloads", PendingIntent.getBroadcast(this, 0,
                Intent(this, DownloadActionReceiver::class.java).setAction(DownloadActionReceiver.PAUSE), PendingIntent.FLAG_IMMUTABLE)).build())
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        setNotification(params, 2001, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
    }
}
