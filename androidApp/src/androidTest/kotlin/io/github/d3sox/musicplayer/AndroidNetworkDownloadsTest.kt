package io.github.d3sox.musicplayer

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.downloads.*
import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.navidrome.*
import io.github.d3sox.musicplayer.playlists.Playlists
import io.github.d3sox.musicplayer.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidNetworkDownloadsTest {
    @Test
    fun downloadsRespectAndroidMeteredWifiAndResumeWhenItBecomesUnmetered() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val policies = shell("cmd netpolicy list wifi-networks").lineSequence().filter { it.startsWith("AndroidWifi;") }.distinct().toList()
        check(policies.size == 1) { "This network test requires the isolated emulator's AndroidWifi network" }
        val original = policies.single().substringAfter(';').let { if (it == "none") "undefined" else it }
        val originalMobileData = shell("settings get global mobile_data").trim()
        check(originalMobileData in listOf("0", "1")) { "Could not read the emulator's mobile-data setting" }
        val name = "network-downloads-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, "network-downloads-${UUID.randomUUID()}")
        val store = openMusicStore(context, name)
        val http = HttpClient(OkHttp)
        var monitor: AndroidDownloadNetwork? = null
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, "http://127.0.0.1:14533", ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val song = client.songs().first()
            val track = library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            val manager = context.getSystemService(ConnectivityManager::class.java)
            shell("svc data disable")
            withTimeout(10_000) {
                while (manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) delay(50)
            }
            shell("cmd netpolicy set metered-network AndroidWifi true")
            withTimeout(10_000) {
                while (manager.getNetworkCapabilities(manager.activeNetwork)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                } != true) delay(50)
            }
            val network = AndroidDownloadNetwork(context)
            monitor = network
            val worker = OfflineMusic(store, library, { client }, directory.path, Playlists(store), network.state)
            offline = worker
            worker.request(listOf(track.id))
            withTimeout(3_000) { worker.state.first { it.containsKey(track.id) } }
            delay(500)
            assertEquals(DownloadStatus.PENDING, worker.state.value[track.id]?.status)
            shell("cmd netpolicy set metered-network AndroidWifi false")
            val complete = withTimeout(10_000) { worker.state.first { it[track.id]?.status == DownloadStatus.COMPLETE } }.getValue(track.id)
            assertEquals(song.sizeBytes, File(complete.path!!).length())
        } finally {
            offline?.closeAndJoin(); monitor?.close(); http.close(); store.close()
            context.deleteDatabase(name); directory.deleteRecursively()
            shell("cmd netpolicy set metered-network AndroidWifi $original")
            shell(if (originalMobileData == "1") "svc data enable" else "svc data disable")
        }
    }
}
