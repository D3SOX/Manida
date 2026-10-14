package me.d3sox.manida

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import io.github.d3sox.musicplayer.settings.MusicPreferences
import io.github.d3sox.musicplayer.playlists.Playlists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidDownloadJobs(context: Context, private val preferences: MusicPreferences, private val playlists: Playlists,
    private val onUserDownload: () -> Unit) {
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val service = ComponentName(context, DownloadService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduling = Mutex()

    fun schedule(userInitiated: Boolean = false) {
        if (userInitiated) onUserDownload()
        scope.launch { scheduling.withLock {
            val settings = preferences.load()
            if (settings.downloadsPaused) {
                scheduler.cancel(DOWNLOAD_JOB)
                scheduler.cancel(PLAYLIST_JOB)
                return@withLock
            }
            val networkType = if (settings.downloadOnMetered) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED
            if (playlists.all().any { it.autoDownload }) {
                val periodic = JobInfo.Builder(PLAYLIST_JOB, service)
                    .setRequiredNetworkType(networkType)
                    .setRequiresStorageNotLow(true)
                    .setPersisted(true)
                    .setPeriodic(15 * 60_000L)
                    .setExtras(PersistableBundle().apply { putBoolean("refresh-playlists", true) })
                    .build()
                if (scheduler.getPendingJob(PLAYLIST_JOB)?.requiredNetwork != periodic.requiredNetwork)
                    scheduler.schedule(periodic)
            } else scheduler.cancel(PLAYLIST_JOB)
            val builder = JobInfo.Builder(DOWNLOAD_JOB, service)
                .setRequiredNetworkType(networkType)
                .setRequiresStorageNotLow(true)
                .setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            val foregroundTransfer = Build.VERSION.SDK_INT >= 34 && userInitiated
            if (Build.VERSION.SDK_INT >= 34) builder.setUserInitiated(foregroundTransfer)
            val job = builder.build()
            val pending = scheduler.getPendingJob(DOWNLOAD_JOB)
            val upgrade = foregroundTransfer && pending?.isUserInitiated != true
            if (pending?.requiredNetwork == job.requiredNetwork && !upgrade) return@withLock
            if (scheduler.schedule(job) == JobScheduler.RESULT_FAILURE && foregroundTransfer)
                scheduler.schedule(builder.setUserInitiated(false).build())
        } }
    }

    private companion object {
        const val DOWNLOAD_JOB = 1001
        const val PLAYLIST_JOB = 1002
    }
}
