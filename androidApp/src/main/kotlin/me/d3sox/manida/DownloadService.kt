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
import io.github.d3sox.musicplayer.downloads.playlistDownloadProgress
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

    // Playlist downloads report how many songs are offline; single songs report their transferred bytes.
    private fun transferProgress(state: AppState): TransferProgress {
        val record = state.downloads.values.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
        val playlists = playlistDownloadProgress(state.playlists, state.tracks, state.downloads, state.server?.serverId, state.excludedDownloads)
            .filter { !it.complete && (record == null || record.trackId in it.playlist.trackIds) }
        if (playlists.isNotEmpty()) {
            val downloaded = playlists.sumOf { it.downloaded }
            val total = playlists.sumOf { it.total }
            val fraction = playlists.sumOf { it.fraction.toDouble() * it.total } / total
            return TransferProgress(playlists.singleOrNull()?.playlist?.name ?: "${playlists.size} playlists",
                "Downloading playlist · $downloaded of $total songs", (fraction * 100).toInt().coerceIn(0, 100))
        }
        if (record == null) return TransferProgress()
        val title = state.tracks.firstOrNull { it.id == record.trackId }?.metadata?.title ?: "Manida"
        val percent = if (record.sizeBytes > 0) (record.bytesDownloaded.toDouble() / record.sizeBytes * 100).toInt().coerceIn(0, 100) else null
        return TransferProgress(title, percent = percent)
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
