package me.d3sox.nevumi

import android.app.job.JobScheduler
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.navidrome.*
import me.d3sox.nevumi.playlists.Playlists
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidBackgroundDownloadsTest {
    @Test
    fun aPeriodicJobDownloadsServerPlaylistEditsWhileTheActivityIsClosed() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as MusicApplication
        val controller = app.controller
        val preferences = MusicPreferences(app.store)
        val original = preferences.load()
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "http://127.0.0.1:14533", ServerCredentials("fixture", "fixture-password"))
        val songs = client.songs().associateBy { it.metadata.title }
        val first = songs.getValue("Long Way Home")
        val added = songs.getValue("Night Walk")
        val remote = client.savePlaylist("Background downloads ${UUID.randomUUID()}", listOf(first.id))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var playlistId: String? = null
        var restoreNetwork: (() -> Unit)? = null
        try {
            controller.updatePreferences { it.copy(downloadsPaused = true, downloadOnMetered = false) }.join()
            controller.connectServer("http://127.0.0.1:14533", "fixture", "fixture-password").join()
            val playlist = controller.state.value.playlists.single { it.server?.id == remote.id }
            playlistId = playlist.id
            controller.setPlaylistDownload(playlist.id, true).join()
            scenario.close()
            client.savePlaylist(remote.name, listOf(first.id, added.id), remote.id)
            restoreNetwork = useUnmeteredWifi()
            check(withTimeoutOrNull(30_000) {
                controller.state.first { it.downloadNetwork.connected && !it.downloadNetwork.metered }
            } != null) { "Expected unmetered Wi-Fi, got ${controller.state.value.downloadNetwork}" }
            // Schedule only after the remote edit: an earlier periodic run can still be active
            // when `jobscheduler run -f` returns, so that command need not start a fresh refresh.
            controller.setDownloadsPaused(false).join()
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val periodic = withTimeout(5_000) {
                while (true) {
                    val pending = scheduler.allPendingJobs.firstOrNull {
                        it.service.className == DownloadService::class.java.name && it.isPeriodic
                    }
                    if (pending != null) return@withTimeout pending
                    delay(50)
                }
                error("Unreachable")
            }
            assertTrue(periodic.isPersisted)
            assertEquals(15 * 60_000L, periodic.intervalMillis)
            val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd jobscheduler run -f ${context.packageName} ${periodic.id}")).bufferedReader().use { it.readText() }
            check("Running job" in output) { output }
            val updated = withTimeout(30_000) { controller.state.first { state ->
                val collection = state.playlists.firstOrNull { it.id == playlist.id }
                collection?.trackIds?.size == 2 && collection.trackIds.all {
                    state.downloads[it]?.status == DownloadStatus.COMPLETE
                }
            } }
            val offlinePlaylist = updated.playlists.single { it.id == playlist.id }
            assertEquals(listOf("Long Way Home", "Night Walk"), offlinePlaylist.trackIds.map { id ->
                updated.tracks.single { it.id == id }.metadata.title
            })
            assertEquals(listOf(24_617L, 264_784L), offlinePlaylist.trackIds.map { id -> File(updated.downloads.getValue(id).path!!).length() })
        } finally {
            playlistId?.let { id -> if (Playlists(app.store).get(id) != null) controller.deletePlaylist(id).join() }
            try { client.deletePlaylist(remote.id) } catch (error: NavidromeException) { if (error.code != 70) throw error }
            controller.updatePreferences { original }.join()
            restoreNetwork?.invoke()
            scenario.close(); http.close()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun anExplicitDownloadUsesAUserInitiatedTransferAndKeepsTheNetworkPreference() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as MusicApplication
        val controller = app.controller
        val preferences = MusicPreferences(app.store)
        val original = preferences.load()
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val policy = shell("cmd netpolicy list wifi-networks").lineSequence().filter { it.startsWith("AndroidWifi;") }.distinct().single()
        val originalMetered = policy.substringAfter(';').let { if (it == "none") "undefined" else it }
        val originalMobile = shell("settings get global mobile_data").trim()
        check(originalMobile in listOf("0", "1"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            shell("svc data disable")
            shell("cmd netpolicy set metered-network AndroidWifi true")
            withTimeout(10_000) { controller.state.first { it.downloadNetwork.connected && it.downloadNetwork.metered } }
            controller.updatePreferences { it.copy(downloadsPaused = false, downloadOnMetered = false) }.join()
            controller.connectServer("http://127.0.0.1:14533", "fixture", "fixture-password").join()
            val track = controller.state.value.tracks.single { it.metadata.title == "Northern Light" && it.sources.any { source -> source.kind == SourceKind.SERVER } }
            controller.removeDownload(track.id).join()
            controller.downloadTracks(listOf(track.id)).join()
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = withTimeout(5_000) {
                while (true) {
                    val pending = scheduler.allPendingJobs.firstOrNull {
                        it.service.className == DownloadService::class.java.name && it.isUserInitiated
                    }
                    if (pending != null) return@withTimeout pending
                    delay(50)
                }
                error("Unreachable")
            }
            assertTrue(job.isPersisted)
            assertTrue(job.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
            scenario.close()
            shell("cmd netpolicy set metered-network AndroidWifi false")
            val completed = withTimeout(30_000) {
                controller.state.first { it.downloads[track.id]?.status == DownloadStatus.COMPLETE }
            }.downloads.getValue(track.id)
            assertEquals(54_509L, File(completed.path!!).length())
            controller.removeDownload(track.id).join()
        } finally {
            controller.updatePreferences { original }.join()
            shell("cmd netpolicy set metered-network AndroidWifi $originalMetered")
            shell(if (originalMobile == "1") "svc data enable" else "svc data disable")
            scenario.close()
        }
    }

    @Test
    fun aPersistedAndroidJobDownloadsMusicWhenTheActivityIsClosed() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as MusicApplication
        val controller = app.controller
        val preferences = MusicPreferences(app.store)
        val original = preferences.load()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var restoreNetwork: (() -> Unit)? = null
        try {
            controller.updatePreferences { it.copy(downloadsPaused = true, downloadOnMetered = false) }.join()
            controller.connectServer("http://127.0.0.1:14533", "fixture", "fixture-password").join()
            val track = controller.state.value.tracks.single { it.metadata.title == "Northern Light" && it.sources.any { source -> source.kind == SourceKind.SERVER } }
            controller.removeDownload(track.id).join()
            controller.downloadTracks(listOf(track.id)).join()
            withTimeout(5_000) { controller.state.first { it.downloads[track.id]?.status == DownloadStatus.PENDING } }
            scenario.close()
            restoreNetwork = useUnmeteredWifi()
            controller.setDownloadsPaused(false).join()
            check(withTimeoutOrNull(30_000) {
                controller.state.first { it.downloadNetwork.connected && !it.downloadNetwork.metered }
            } != null) { "Expected unmetered Wi-Fi, got ${controller.state.value.downloadNetwork}" }
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = withTimeout(5_000) {
                while (true) {
                    val pending = scheduler.allPendingJobs.firstOrNull {
                        it.service.className == DownloadService::class.java.name && !it.isPeriodic
                    }
                    if (pending != null) return@withTimeout pending
                    delay(50)
                }
                error("Unreachable")
            }
            assertTrue(job.isPersisted)
            assertTrue(job.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
            val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd jobscheduler run -f ${context.packageName} ${job.id}")).bufferedReader().use { it.readText() }
            check("Running job" in output) { output }
            val completed = withTimeout(20_000) {
                controller.state.first { it.downloads[track.id]?.status == DownloadStatus.COMPLETE }
            }.downloads.getValue(track.id)
            assertEquals(54_509L, File(completed.path!!).length())
            val header = ByteArray(4)
            File(completed.path!!).inputStream().use { java.io.DataInputStream(it).readFully(header) }
            assertEquals("fLaC", header.decodeToString())
            controller.removeDownload(track.id).join()
        } finally {
            scenario.close()
            controller.updatePreferences { original }.join()
            restoreNetwork?.invoke()
        }
    }

    /** Select Wi-Fi explicitly; Android can prefer validated cellular data while Wi-Fi validation is pending. */
    private fun useUnmeteredWifi(): () -> Unit {
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val originalMetered = shell("cmd netpolicy list wifi-networks").lineSequence().firstOrNull { it.startsWith("AndroidWifi;") }
            ?.substringAfter(';')?.let { if (it == "none") "undefined" else it } ?: "undefined"
        val wifiWasOn = shell("settings get global wifi_on").trim() != "0"
        val originalMobile = shell("settings get global mobile_data").trim()
        check(originalMobile in listOf("0", "1"))
        val restore: () -> Unit = {
            shell("cmd netpolicy set metered-network AndroidWifi $originalMetered")
            if (!wifiWasOn) shell("svc wifi disable")
            shell(if (originalMobile == "1") "svc data enable" else "svc data disable")
        }
        try {
            shell("svc data disable")
            shell("svc wifi enable")
            shell("cmd netpolicy set metered-network AndroidWifi false")
        } catch (failure: Throwable) {
            runCatching { restore() }
            throw failure
        }
        return restore
    }
}
