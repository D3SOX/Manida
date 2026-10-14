package io.github.d3sox.musicplayer.downloads

import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.navidrome.*
import io.github.d3sox.musicplayer.playlists.Playlists
import io.github.d3sox.musicplayer.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistDownloadsTest {
    @Test
    fun anAutomaticDownloadSelectsTheConnectedServersCopyOfAMixedTrack() = runBlocking {
        val directory = Files.createTempDirectory("playlist-server-ownership")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533", ServerCredentials("fixture", "fixture-password"))
            val songs = client.songs().associateBy { it.metadata.title }
            val current = songs.getValue("Northern Light")
            val colliding = songs.getValue("Long Way Home")
            val previous = NavidromeClient(http, "https://previous.example", ServerCredentials("test", "test"))
            val library = MusicLibrary(store)
            val track = library.importTrack(current.metadata,
                TrackSource("server:${previous.serverId}:${colliding.id}", SourceKind.SERVER, colliding.id))
            library.importTrack(current.metadata,
                TrackSource("server:${client.serverId}:${current.id}", SourceKind.SERVER, current.id), linkTo = track.id)
            val playlists = Playlists(store)
            val playlist = playlists.create("Keep the connected copy", listOf(track.id))
            playlists.setAutoDownload(playlist.id, true)
            val downloads = AudioDownloads(store, library, client, directory.resolve("audio").toString())
            PlaylistDownloads(playlists, library, downloads, client).synchronize()
            val complete = downloads.state(track.id)!!
            assertEquals(54_509L, Files.size(java.nio.file.Path.of(complete.path!!)))
            assertEquals("fLaC", Files.newInputStream(java.nio.file.Path.of(complete.path)).use { it.readNBytes(4).decodeToString() })
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aMissingLocalCopyIsReplacedFromTheServerForAnAutomaticallyDownloadedPlaylist() = runBlocking {
        val directory = Files.createTempDirectory("missing-playlist-audio")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533", ServerCredentials("fixture", "fixture-password"))
            val song = client.songs().single { it.metadata.title == "Northern Light" }
            val localFile = directory.resolve("local.flac")
            Files.copy(java.nio.file.Path.of(System.getProperty("musicplayer.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac"), localFile)
            val library = MusicLibrary(store)
            val track = library.importTrack(song.metadata, TrackSource("local", SourceKind.LOCAL, localFile.toString()))
            library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id), linkTo = track.id)
            val playlists = Playlists(store)
            val playlist = playlists.create("Keep offline", listOf(track.id))
            playlists.setAutoDownload(playlist.id, true)
            Files.delete(localFile)
            val downloads = AudioDownloads(store, library, client, directory.resolve("audio").toString())
            PlaylistDownloads(playlists, library, downloads, client).synchronize()
            val replacement = downloads.state(track.id)
            assertEquals(DownloadStatus.COMPLETE, replacement?.status)
            assertEquals(song.sizeBytes, replacement?.path?.let { Files.size(java.nio.file.Path.of(it)) })
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playlistChangesDownloadAdditionsAndReleaseOnlyThatPlaylistsRemovedTracks() = runBlocking {
        val directory = Files.createTempDirectory("playlist-changes-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val songs = client.songs().associateBy { it.metadata.title }
            val library = MusicLibrary(store)
            val tracks = songs.mapValues { (_, song) ->
                library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            }
            val manual = tracks.getValue("Northern Light")
            val removed = tracks.getValue("Night Walk")
            val added = tracks.getValue("Long Way Home")
            val downloads = AudioDownloads(store, library, client, directory.resolve("audio").toString())
            downloads.download(manual.id, songs.getValue("Northern Light"))
            downloads.pin(manual.id, "manual")
            val playlists = Playlists(store)
            val playlist = playlists.create("Evening", listOf(manual.id, removed.id))
            playlists.setAutoDownload(playlist.id, true)
            val automatic = PlaylistDownloads(playlists, library, downloads, client)
            automatic.synchronize()
            playlists.replaceTracks(playlist.id, listOf(added.id))
            automatic.synchronize()
            downloads.enforceCacheBudget(0)

            assertEquals(setOf("Northern Light", "Long Way Home"), library.tracks().filter {
                SourceResolver.resolve(it.sources, online = false) != null
            }.map { it.metadata.title }.toSet())
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anAutomaticallyDownloadedMixedPlaylistRemainsAvailableOfflineAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("playlist-download-test")
        val path = directory.resolve("library.db").toString()
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val songs = client.songs()
            val store = openMusicStore(path)
            val library = MusicLibrary(store)
            val local = library.importTrack(TrackMetadata("Northern Light"), TrackSource("local-flac",
                SourceKind.LOCAL, System.getProperty("musicplayer.testRoot") +
                    "/.test-environment/music/Fixture Artist/Fixture Album/01 Northern Light.flac"))
            val remote = songs.filter { it.metadata.title != "Northern Light" }.map { song ->
                library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            }
            val playlists = Playlists(store)
            val playlist = playlists.create("Evening", remote.map { it.id } + local.id)
            playlists.setAutoDownload(playlist.id, true)
            PlaylistDownloads(playlists, library,
                AudioDownloads(store, library, client, directory.resolve("audio").toString()), client).synchronize()
            store.close()

            val restored = openMusicStore(path)
            try {
                assertEquals(setOf("Night Walk", "Long Way Home", "Northern Light"),
                    MusicLibrary(restored).tracks().filter {
                        SourceResolver.resolve(it.sources, online = false) != null
                    }.map { it.metadata.title }.toSet())
            } finally { restored.close() }
        } finally { http.close(); directory.toFile().deleteRecursively() }
    }
}
