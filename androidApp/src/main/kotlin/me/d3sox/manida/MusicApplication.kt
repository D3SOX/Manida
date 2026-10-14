package me.d3sox.manida

import android.app.Application
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.history.ListeningHistory
import io.github.d3sox.musicplayer.history.Scrobbling
import io.github.d3sox.musicplayer.library.AndroidLocalSources
import io.github.d3sox.musicplayer.library.AndroidFileEdits
import io.github.d3sox.musicplayer.navidrome.NavidromeConnection
import io.github.d3sox.musicplayer.navidrome.NavidromeChanges
import io.github.d3sox.musicplayer.playback.AndroidPlaybackEngine
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.security.AndroidCredentialVault
import io.github.d3sox.musicplayer.storage.MusicStore
import io.github.d3sox.musicplayer.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.github.d3sox.musicplayer.downloads.OfflineMusic
import io.github.d3sox.musicplayer.downloads.AndroidDownloadNetwork
import io.github.d3sox.musicplayer.playlists.Playlists
import java.io.File
import io.github.d3sox.musicplayer.library.AndroidAudioAccess
import io.github.d3sox.musicplayer.settings.MusicPreferences
import io.github.d3sox.musicplayer.artwork.ArtworkLibrary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import io.github.d3sox.musicplayer.sftp.SftpConnection
import android.net.Uri

class MusicApplication : Application() {
    lateinit var controller: MusicController
        private set
    lateinit var store: MusicStore
        private set
    lateinit var offline: OfflineMusic
        private set
    private lateinit var http: HttpClient
    private lateinit var downloadNetwork: AndroidDownloadNetwork
    private lateinit var connection: NavidromeConnection
    private lateinit var serverChanges: NavidromeChanges
    private lateinit var downloadJobs: AndroidDownloadJobs
    private lateinit var serverFiles: SftpConnection
    lateinit var mediaWriteRequests: MediaWriteRequests
        private set
    lateinit var localNetworkRequests: LocalNetworkRequests
        private set
    private val mutableDownloadNotifications = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val downloadNotificationsRequested = mutableDownloadNotifications.asSharedFlow()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        mediaWriteRequests = MediaWriteRequests(this)
        localNetworkRequests = LocalNetworkRequests(this)
        store = openMusicStore(this)
        http = HttpClient(OkHttp)
        val library = MusicLibrary(store)
        val credentials = AndroidCredentialVault(this)
        val artwork = ArtworkLibrary(File(filesDir, "artwork").path)
        connection = NavidromeConnection(store, http, credentials)
        serverChanges = NavidromeChanges(store, library, connection::client)
        serverFiles = SftpConnection(store, library, connection::client, credentials, File(filesDir, "server-edits").path,
            readLocal = { location ->
                if (location.startsWith("content:")) requireNotNull(contentResolver.openInputStream(Uri.parse(location))) { "The local music file is unavailable" }
                else File(location).inputStream()
            }, changes = serverChanges, artwork = artwork)
        val playlists = Playlists(store)
        downloadNetwork = AndroidDownloadNetwork(this)
        downloadJobs = AndroidDownloadJobs(this, MusicPreferences(store), playlists) { mutableDownloadNotifications.tryEmit(Unit) }
        offline = OfflineMusic(store, library, connection::client, File(filesDir, "audio").path, playlists, downloadNetwork.state,
            sourceAccessible = AndroidAudioAccess(this)::available, scheduleWork = downloadJobs::schedule)
        controller = MusicController(library, PlaybackQueue(store),
            AndroidPlaybackEngine(this, PlaybackService::class.java),
            connection, AndroidLocalSources(this, library, artwork), offline, playlists, AndroidAudioAccess(this)::available,
            MusicPreferences(store), artwork, serverChanges,
            AndroidFileEdits(this, store, library, File(filesDir, "file-edits").path, mediaWriteRequests::request, artwork),
            listeningHistory = ListeningHistory(store, library),
            scrobbling = Scrobbling(store, library, http, credentials, downloadNetwork.state, connection::client),
            equalizerProfiles = io.github.d3sox.musicplayer.playback.AutoEqProfiles(store, http), serverFiles = serverFiles,
            pairedDevices = io.github.d3sox.musicplayer.sync.JvmPairedDevices(store,
                io.github.d3sox.musicplayer.sync.LibrarySync(store, library, serverChanges, AndroidAudioAccess(this)::contentId), credentials, "Android phone",
                networkAccess = localNetworkRequests::request),
            networkAccess = localNetworkRequests::request)
    }

    suspend fun refreshOfflinePlaylists() {
        connection.client()?.let { serverChanges.refreshLibrary(it, includeFilePaths = serverFiles.configuration()?.serverId == it.serverId) }
    }

    fun retryUserDownloads() { downloadJobs.schedule(userInitiated = true) }
}
