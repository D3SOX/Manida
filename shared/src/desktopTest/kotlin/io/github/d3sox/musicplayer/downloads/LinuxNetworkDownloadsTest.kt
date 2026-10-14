package io.github.d3sox.musicplayer.downloads

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
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DBusInterfaceName("org.freedesktop.NetworkManager")
@DBusProperty(name = "State", type = UInt32::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Metered", type = UInt32::class, access = DBusProperty.Access.READ)
interface NetworkManagerFixture : DBusInterface

class NetworkManagerPropertiesFixture : NetworkManagerFixture, Properties {
    var metered = 1L
    override fun getObjectPath() = "/org/freedesktop/NetworkManager"
    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = mapOf("State" to Variant(UInt32(50)), "Metered" to Variant(UInt32(metered)))
    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A = GetAll(interfaceName).getValue(propertyName) as A
    override fun <A> Set(interfaceName: String, propertyName: String, value: A) = error("Read only")
}

class LinuxNetworkDownloadsTest {
    @Test
    fun downloadsWaitOnMeteredLinuxConnectionsAndStartOnAnUnmeteredLocalNetwork() = runBlocking {
        val directory = Files.createTempDirectory("linux-network-downloads")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        assertTrue(daemon.inputStream.bufferedReader().readLine().startsWith("unix:path="))
        val bus = DBusConnectionBuilder.forAddress(address).withShared(false).build()
        val properties = NetworkManagerPropertiesFixture()
        bus.exportObject(properties.objectPath, properties)
        bus.requestBusName("org.freedesktop.NetworkManager")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val http = HttpClient(OkHttp)
        var network: LinuxDownloadNetwork? = null
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val song = client.songs().first()
            val track = library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            val monitor = LinuxDownloadNetwork(address)
            network = monitor
            val worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store), monitor.state)
            offline = worker
            worker.request(listOf(track.id))
            withTimeout(3_000) { worker.state.first { it.containsKey(track.id) } }
            delay(500)
            assertEquals(DownloadStatus.PENDING, worker.state.value[track.id]?.status)
            properties.metered = 4
            bus.sendMessage(Properties.PropertiesChanged(properties.objectPath, "org.freedesktop.NetworkManager",
                mapOf("Metered" to Variant(UInt32(4))), emptyList()))
            val downloaded = withTimeout(3_000) { worker.state.first { it[track.id]?.status == DownloadStatus.COMPLETE } }.getValue(track.id)
            assertEquals(song.sizeBytes, Files.size(Path.of(downloaded.path!!)))
        } finally {
            offline?.closeAndJoin(); network?.close(); http.close(); store.close(); bus.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }
}
