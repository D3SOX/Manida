package me.d3sox.nevumi.sync

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

fun main(arguments: Array<String>): Unit = runBlocking {
    val directory = File(arguments.single()).apply { mkdirs() }
    val store = openMusicStore(File(directory, "library.db").path)
    val music = MusicLibrary(store)
    val vault = object : CredentialVault {
        private val secrets = ConcurrentHashMap<String, String>()
        override suspend fun save(key: String, secret: String) { secrets[key] = secret }
        override suspend fun read(key: String) = secrets[key]
        override suspend fun delete(key: String) { secrets.remove(key) }
    }
    val devices = JvmPairedDevices(store, LibrarySync(store, music), vault, "Linux desktop")
    val track = music.importTrack(TrackMetadata("Night walk", durationMs = 120_000), TrackSource("server:fixture:night", SourceKind.SERVER, "night"))
    music.setFavorite(track.id, true)
    devices.createInvitation("127.0.0.1")
    File(directory, "port").writeText(devices.state.value.listeningPort.toString())
    val control = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    control.createContext("/invitation") { exchange ->
        val bytes = runBlocking { devices.createInvitation("127.0.0.1").toByteArray(Charsets.UTF_8) }
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    control.start()
    File(directory, "control-port").writeText(control.address.port.toString())
    println("PAIRED_FIXTURE_READY")
    try { awaitCancellation() }
    finally { control.stop(0); devices.closeAndJoin(); store.close() }
}
