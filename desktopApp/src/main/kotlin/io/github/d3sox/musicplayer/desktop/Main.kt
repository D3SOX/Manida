package io.github.d3sox.musicplayer.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.history.ListeningHistory
import io.github.d3sox.musicplayer.history.Scrobbling
import io.github.d3sox.musicplayer.library.WatchingLocalFiles
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.library.LocalFileEdits
import io.github.d3sox.musicplayer.library.LocalFiles
import io.github.d3sox.musicplayer.navidrome.NavidromeConnection
import io.github.d3sox.musicplayer.navidrome.NavidromeChanges
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.playback.CrossfadePlaybackEngine
import io.github.d3sox.musicplayer.playback.LinuxMediaSession
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.security.SecretServiceVault
import io.github.d3sox.musicplayer.storage.openMusicStore
import io.github.d3sox.musicplayer.ui.MusicPlayerApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import io.github.d3sox.musicplayer.downloads.OfflineMusic
import io.github.d3sox.musicplayer.downloads.LinuxDownloadNetwork
import io.github.d3sox.musicplayer.downloads.DownloadNetwork
import io.github.d3sox.musicplayer.playlists.Playlists
import io.github.d3sox.musicplayer.settings.MusicPreferences
import io.github.d3sox.musicplayer.artwork.ArtworkLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

fun main(arguments: Array<String>) {
    fun option(name: String) = arguments.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    val base = System.getenv("XDG_DATA_HOME") ?: File(System.getProperty("user.home"), ".local/share").path
    val directory = File(option("--data-dir") ?: "$base/io.github.d3sox.musicplayer").apply { mkdirs() }
    val store = openMusicStore(File(directory, "music-player.db").path)
    val library = MusicLibrary(store)
    val http = HttpClient(OkHttp)
    val credentials = SecretServiceVault()
    val connection = NavidromeConnection(store, http, credentials)
    val serverChanges = NavidromeChanges(store, library, connection::client)
    val playlists = Playlists(store)
    val artwork = ArtworkLibrary(File(directory, "artwork").path)
    val downloadNetwork = try { LinuxDownloadNetwork() }
        catch (error: Exception) { System.err.println("NetworkManager monitoring unavailable: ${error.message}"); null }
    val offline = OfflineMusic(store, library, connection::client, File(directory, "audio").path, playlists,
        downloadNetwork?.state ?: MutableStateFlow(DownloadNetwork()))
    val controller = MusicController(library, PlaybackQueue(store),
        CrossfadePlaybackEngine { MpvPlaybackEngine(option("--audio-output") ?: "auto") },
        connection, WatchingLocalFiles(library, artwork), offline, playlists, preferences = MusicPreferences(store), artwork = artwork,
        serverChanges = serverChanges,
        fileEdits = LocalFileEdits(store, library, File(directory, "file-edits").path, localSources = LocalFiles(library, artwork)),
        listeningHistory = ListeningHistory(store, library),
        scrobbling = Scrobbling(store, library, http, credentials, offline.network, connection::client),
        equalizerProfiles = io.github.d3sox.musicplayer.playback.AutoEqProfiles(store, http),
        serverFiles = io.github.d3sox.musicplayer.sftp.SftpConnection(store, library, connection::client, credentials,
            File(directory, "server-edits").path, changes = serverChanges, artwork = artwork),
        pairedDevices = io.github.d3sox.musicplayer.sync.JvmPairedDevices(store,
            io.github.d3sox.musicplayer.sync.LibrarySync(store, library, serverChanges), credentials, "Linux desktop"))
    option("--import-folder")?.let { controller.importSource(it) }
    val mediaSession = try { LinuxMediaSession(controller) }
        catch (error: Exception) { System.err.println("Desktop media controls unavailable: ${error.message}"); null }
    application {
        val lifecycle = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        Window(title = "Manida", icon = painterResource("manida.png"), state = rememberWindowState(size = DpSize(1100.dp, 800.dp)),
            onCloseRequest = {
                if (!closing) {
                    closing = true
                    lifecycle.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                try { mediaSession?.close(); controller.closeAndJoin() }
                                finally { downloadNetwork?.close(); http.close(); store.close() }
                            }
                        } finally { exitApplication() }
                    }
                }
            }) {
            MusicPlayerApp(controller)
        }
    }
}
