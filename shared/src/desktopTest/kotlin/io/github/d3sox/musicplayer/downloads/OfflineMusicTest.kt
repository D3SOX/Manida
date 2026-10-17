package io.github.d3sox.musicplayer.downloads

import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.navidrome.*
import io.github.d3sox.musicplayer.playlists.Playlists
import io.github.d3sox.musicplayer.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import io.github.d3sox.musicplayer.settings.MusicPreferences
import java.util.concurrent.atomic.AtomicBoolean
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import kotlinx.coroutines.channels.Channel

class OfflineMusicTest {
    @Test
    fun anUnavailableServerReportsFailedDownloadsWithoutEscapingTheWorker() = runBlocking {
        val directory = Files.createTempDirectory("download-server-outage")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(MockEngine { respond("Unavailable", HttpStatusCode.ServiceUnavailable) })
        val library = MusicLibrary(store)
        val client = NavidromeClient(http, "http://localhost", ServerCredentials("fixture", "fixture-password"))
        val track = library.importTrack(TrackMetadata("Unavailable song"), TrackSource("server:${client.serverId}:song", SourceKind.SERVER, "song"))
        val offline = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store), scheduleWork = {})
        try {
            offline.request(listOf(track.id))
            offline.runPending()
            withTimeout(3_000) { offline.state.first { it[track.id]?.status == DownloadStatus.FAILED } }
            assertEquals(DownloadStatus.FAILED, offline.state.value.getValue(track.id).status)
        } finally { offline.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anAutomaticallyDownloadedSmartPlaylistFollowsNewFavoritesAndReleasesOldProtection() = runBlocking {
        val directory = Files.createTempDirectory("smart-playlist-downloads")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        var worker: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val tracks = client.songs().associate { song -> song.metadata.title to
                library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id)) }
            val playlists = Playlists(store)
            var current = tracks.getValue("Northern Light")
            library.setFavorite(current.id, true)
            val playlist = playlists.createSmart("Offline favorites", io.github.d3sox.musicplayer.library.LibraryQuery(favoritesOnly = true))
            playlists.setAutoDownload(playlist.id, true)
            val downloads = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), playlists)
            worker = downloads
            withTimeout(5_000) { downloads.state.first { it[current.id]?.status == DownloadStatus.COMPLETE } }
            for (title in listOf("Long Way Home", "Night Walk")) {
                library.setFavorite(current.id, false)
                current = tracks.getValue(title)
                library.setFavorite(current.id, true)
                withTimeout(5_000) { downloads.state.first { it[current.id]?.status == DownloadStatus.COMPLETE } }
            }
            downloads.enforceCacheBudget(0)
            assertEquals(listOf("Night Walk"), library.tracks().filter {
                SourceResolver.resolve(it.sources, online = false) != null
            }.map { it.metadata.title })
        } finally { worker?.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun requestingAMissingCompletedDownloadRestoresItsAudioAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("missing-manual-download")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val song = client.songs().single { it.metadata.title == "Northern Light" }
            val track = library.importTrack(song.metadata,
                TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            val before = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store), scheduleWork = {})
            offline = before
            before.request(listOf(track.id))
            before.runPending()
            val downloaded = before.state.value.getValue(track.id)
            assertEquals(DownloadStatus.COMPLETE, downloaded.status)
            before.closeAndJoin(); store.close()
            Files.delete(Path.of(downloaded.path!!))
            store = openMusicStore(database)
            val restoredLibrary = MusicLibrary(store)
            val restored = OfflineMusic(store, restoredLibrary, { client }, directory.resolve("audio").toString(), Playlists(store), scheduleWork = {})
            offline = restored
            restored.request(listOf(track.id))
            restored.runPending()
            val recovered = restored.state.value.getValue(track.id)
            assertEquals(DownloadStatus.COMPLETE, recovered.status)
            assertEquals(true, Files.isRegularFile(Path.of(recovered.path!!)))
            assertEquals(song.sizeBytes, Files.size(Path.of(recovered.path)))
            restored.enforceCacheBudget(0)
            assertEquals(recovered.path, SourceResolver.resolve(restoredLibrary.findTrack(track.id)!!.sources, online = false)?.location)
        } finally { offline?.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anExplicitDownloadUsesTheConnectedServerInsteadOfAnOldServersCollidingSongId() = runBlocking {
        val directory = Files.createTempDirectory("manual-download-server-ownership")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val songs = client.songs().associateBy { it.metadata.title }
            val current = songs.getValue("Northern Light")
            val colliding = songs.getValue("Long Way Home")
            val previous = NavidromeClient(http, "https://previous.example", ServerCredentials("test", "test"))
            val library = MusicLibrary(store)
            val track = library.importTrack(current.metadata,
                TrackSource("server:${previous.serverId}:${colliding.id}", SourceKind.SERVER, colliding.id))
            library.importTrack(current.metadata,
                TrackSource("server:${client.serverId}:${current.id}", SourceKind.SERVER, current.id), linkTo = track.id)
            val worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store), scheduleWork = {})
            offline = worker
            worker.request(listOf(track.id))
            worker.runPending()
            val complete = worker.state.value.getValue(track.id)
            assertEquals(DownloadStatus.COMPLETE, complete.status)
            assertEquals(-1L, Files.mismatch(Path.of(System.getProperty("musicplayer.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac"), Path.of(complete.path!!)))
        } finally { offline?.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anOperatingSystemScheduledDownloadWaitsForAdmissionAndRestoresItsPlanAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("scheduled-download")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        val scheduled = Channel<Unit>(Channel.CONFLATED)
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val song = client.songs().single { it.metadata.title == "Northern Light" }
            val track = library.importTrack(song.metadata,
                TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            val before = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store),
                scheduleWork = { scheduled.trySend(Unit) })
            offline = before
            before.request(listOf(track.id))
            withTimeout(3_000) { scheduled.receive() }
            delay(200)
            assertEquals(DownloadStatus.PENDING, before.state.value[track.id]?.status)
            assertEquals(null, SourceResolver.resolve(library.findTrack(track.id)!!.sources, online = false))
            before.closeAndJoin(); store.close()
            store = openMusicStore(database)
            val restoredLibrary = MusicLibrary(store)
            val restored = OfflineMusic(store, restoredLibrary, { client }, directory.resolve("audio").toString(), Playlists(store),
                scheduleWork = { scheduled.trySend(Unit) })
            offline = restored
            restored.runPending()
            val complete = restored.state.value.getValue(track.id)
            assertEquals(DownloadStatus.COMPLETE, complete.status)
            assertEquals(song.sizeBytes, Files.size(Path.of(complete.path!!)))
            restored.enforceCacheBudget(0)
            assertEquals(SourceKind.DOWNLOAD, SourceResolver.resolve(restoredLibrary.findTrack(track.id)!!.sources, online = false)?.kind)
        } finally { offline?.closeAndJoin(); http.close(); store.close(); scheduled.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun cancellingAnActiveDownloadDeletesItsPartialAudioAndAnExplicitRequestStartsAgain() = runBlocking {
        val directory = Files.createTempDirectory("cancel-download")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val audio = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val body = ByteChannel(autoFlush = true)
        body.writeFully(audio.copyOfRange(0, 4))
        val firstStream = AtomicBoolean(true)
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("search3.view") -> respond(
                    if (request.url.parameters["songOffset"] == "0")
                        """{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"song","title":"Cancelled song","suffix":"flac","size":8}]}}}"""
                    else """{"subsonic-response":{"status":"ok","searchResult3":{}}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
                firstStream.compareAndSet(true, false) -> respond(body, headers = headersOf(HttpHeaders.ContentType, "audio/flac"))
                else -> respond(audio, headers = headersOf(HttpHeaders.ContentType, "audio/flac"))
            }
        })
        val client = NavidromeClient(http, "https://music.example", ServerCredentials("test", "test"))
        val library = MusicLibrary(store)
        val track = library.importTrack(TrackMetadata("Cancelled song"), TrackSource("server:${client.serverId}:song", SourceKind.SERVER, "song"))
        val worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store))
        try {
            worker.request(listOf(track.id))
            withTimeout(3_000) { worker.state.first { it[track.id]?.bytesDownloaded == 4L } }
            worker.remove(track.id)
            assertEquals(false, worker.state.value.containsKey(track.id))
            assertEquals(emptyList<Path>(), Files.list(directory.resolve("audio")).use { it.toList() })
            worker.request(listOf(track.id))
            val downloaded = withTimeout(3_000) { worker.state.first { it[track.id]?.status == DownloadStatus.COMPLETE } }.getValue(track.id)
            assertContentEquals(audio, Files.readAllBytes(Path.of(downloaded.path!!)))
        } finally {
            worker.closeAndJoin(); body.cancel(CancellationException("Test complete"))
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun theRestoredQueueSongStaysCachedWhenTheAppReopensOfflineWithCachingDisabled() = runBlocking {
        val directory = Files.createTempDirectory("restored-queue-cache")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val song = client.songs().first()
            val track = library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            PlaybackQueue(store).replace(listOf(track.id))
            val worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store))
            offline = worker
            worker.useTrack(track.id)
            worker.request(listOf(track.id), owner = null)
            val downloaded = withTimeout(3_000) { worker.state.first { it[track.id]?.status == DownloadStatus.COMPLETE } }.getValue(track.id)
            MusicPreferences(store).update { it.copy(cacheBytes = 0) }
            worker.closeAndJoin(); store.close()
            store = openMusicStore(database)
            val restoredLibrary = MusicLibrary(store)
            val restored = OfflineMusic(store, restoredLibrary, { null }, directory.resolve("audio").toString(), Playlists(store))
            offline = restored
            restored.enforceCacheBudget(0)
            assertEquals(downloaded.path, SourceResolver.resolve(restoredLibrary.findTrack(track.id)!!.sources, online = false)?.location)
            assertEquals(song.sizeBytes, Files.size(Path.of(downloaded.path!!)))
        } finally { offline?.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun losingTheNetworkPausesATransferAndAllowingMeteredDownloadsResumesItsSavedBytes() = runBlocking {
        val directory = Files.createTempDirectory("network-resume-download")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val audio = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val body = ByteChannel(autoFlush = true)
        body.writeFully(audio.copyOfRange(0, 4))
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("search3.view") -> respond(
                    if (request.url.parameters["songOffset"] == "0")
                        """{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"song","title":"Network song","suffix":"flac","size":8}]}}}"""
                    else """{"subsonic-response":{"status":"ok","searchResult3":{}}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.headers[HttpHeaders.Range] == "bytes=4-" && request.headers[HttpHeaders.IfRange] == "\"audio-v1\"" -> respond(audio.copyOfRange(4, 8),
                    HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentType to listOf("audio/flac"),
                        HttpHeaders.ContentRange to listOf("bytes 4-7/8"), HttpHeaders.ETag to listOf("\"audio-v1\"")))
                else -> respond(body, headers = headersOf(HttpHeaders.ContentType to listOf("audio/flac"), HttpHeaders.ETag to listOf("\"audio-v1\"")))
            }
        })
        val client = NavidromeClient(http, "https://music.example", ServerCredentials("test", "test"))
        val network = MutableStateFlow(DownloadNetwork())
        val library = MusicLibrary(store)
        val track = library.importTrack(TrackMetadata("Network song"), TrackSource("server:${client.serverId}:song", SourceKind.SERVER, "song"))
        val worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store), network)
        try {
            worker.request(listOf(track.id))
            withTimeout(3_000) { worker.state.first { it[track.id]?.bytesDownloaded == 4L } }
            network.value = DownloadNetwork(connected = false)
            withTimeout(3_000) { worker.state.first { it[track.id]?.status == DownloadStatus.PAUSED } }
            network.value = DownloadNetwork(connected = true, metered = true)
            delay(500)
            assertEquals(DownloadStatus.PAUSED, worker.state.value[track.id]?.status)
            MusicPreferences(store).update { it.copy(downloadOnMetered = true) }
            val downloaded = withTimeout(3_000) { worker.state.first { it[track.id]?.status == DownloadStatus.COMPLETE } }.getValue(track.id)
            assertContentEquals(audio, Files.readAllBytes(Path.of(downloaded.path!!)))
        } finally {
            worker.closeAndJoin(); body.cancel(CancellationException("Test complete"))
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun downloadsWaitOnAMeteredConnectionThenStartWhenAnUnmeteredNetworkArrives() = runBlocking {
        val directory = Files.createTempDirectory("download-network-policy")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val song = client.songs().first()
            val track = library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            val network = MutableStateFlow(DownloadNetwork(connected = true, metered = true))
            val worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store), network)
            offline = worker
            worker.request(listOf(track.id))
            withTimeout(3_000) { worker.state.first { it.containsKey(track.id) } }
            delay(500)
            assertEquals(DownloadStatus.PENDING, worker.state.value[track.id]?.status)
            assertEquals(null, SourceResolver.resolve(library.findTrack(track.id)!!.sources, online = false))
            network.value = DownloadNetwork(connected = true, metered = false)
            val downloaded = withTimeout(3_000) { worker.state.first { it[track.id]?.status == DownloadStatus.COMPLETE } }.getValue(track.id)
            assertEquals(song.sizeBytes, Files.size(Path.of(downloaded.path!!)))
        } finally { offline?.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun deletingAnAutomaticPlaylistReleasesItsCacheProtectionEvenWhileDownloadsArePaused() = runBlocking {
        val directory = Files.createTempDirectory("paused-deleted-playlist-cache")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val song = client.songs().first()
            val track = library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            val playlists = Playlists(store)
            val playlist = playlists.create("Automatic", listOf(track.id))
            playlists.setAutoDownload(playlist.id, true)
            val worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), playlists)
            offline = worker
            withTimeout(3_000) { worker.state.first { it[track.id]?.status == DownloadStatus.COMPLETE } }
            worker.pause()
            playlists.delete(playlist.id)
            MusicPreferences(store).update { it.copy(cacheBytes = 0) }
            withTimeout(3_000) { worker.state.first { it.isEmpty() } }
            assertEquals(null, SourceResolver.resolve(library.findTrack(track.id)!!.sources, online = false))
            assertEquals(true, MusicPreferences(store).load().downloadsPaused)
        } finally { offline?.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun deletingAnAutomaticPlaylistWhileOfflineReleasesItsCacheProtection() = runBlocking {
        val directory = Files.createTempDirectory("deleted-playlist-cache")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val song = client.songs().first()
            val track = library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            val playlists = Playlists(store)
            val playlist = playlists.create("Automatic", listOf(track.id))
            playlists.setAutoDownload(playlist.id, true)
            val connected = AtomicBoolean(true)
            val worker = OfflineMusic(store, library, { client.takeIf { connected.get() } }, directory.resolve("audio").toString(), playlists)
            offline = worker
            withTimeout(3_000) { worker.state.first { it[track.id]?.status == DownloadStatus.COMPLETE } }
            connected.set(false)
            playlists.delete(playlist.id)
            MusicPreferences(store).update { it.copy(cacheBytes = 0) }
            withTimeout(3_000) { worker.state.first { it.isEmpty() } }
            assertEquals(null, SourceResolver.resolve(library.findTrack(track.id)!!.sources, online = false))
        } finally { offline?.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun pausingATransferSurvivesRestartAndResumingContinuesFromItsSavedBytes() = runBlocking {
        val directory = Files.createTempDirectory("paused-download")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val audio = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val body = ByteChannel(autoFlush = true)
        body.writeFully(audio.copyOfRange(0, 4))
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("search3.view") -> respond(
                    if (request.url.parameters["songOffset"] == "0")
                        """{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"song","title":"Paused song","suffix":"flac","size":8}]}}}"""
                    else """{"subsonic-response":{"status":"ok","searchResult3":{}}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.headers[HttpHeaders.Range] == "bytes=4-" && request.headers[HttpHeaders.IfRange] == "\"audio-v1\"" -> respond(audio.copyOfRange(4, 8),
                    HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentType to listOf("audio/flac"),
                        HttpHeaders.ContentRange to listOf("bytes 4-7/8"), HttpHeaders.ETag to listOf("\"audio-v1\"")))
                else -> respond(body, headers = headersOf(HttpHeaders.ContentType to listOf("audio/flac"), HttpHeaders.ETag to listOf("\"audio-v1\"")))
            }
        })
        val client = NavidromeClient(http, "https://music.example", ServerCredentials("test", "test"))
        var offline: OfflineMusic? = null
        try {
            val library = MusicLibrary(store)
            val track = library.importTrack(TrackMetadata("Paused song"), TrackSource("server:${client.serverId}:song", SourceKind.SERVER, "song"))
            val worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store))
            offline = worker
            worker.request(listOf(track.id))
            withTimeout(3_000) { worker.state.first { it[track.id]?.bytesDownloaded == 4L } }
            worker.pause()
            withTimeout(3_000) { worker.state.first { it[track.id]?.status == DownloadStatus.PAUSED } }
            worker.closeAndJoin(); store.close()
            store = openMusicStore(database)
            val restored = OfflineMusic(store, MusicLibrary(store), { client }, directory.resolve("audio").toString(), Playlists(store))
            offline = restored
            assertEquals(true, MusicPreferences(store).load().downloadsPaused)
            withTimeout(3_000) { restored.state.first { it[track.id]?.status == DownloadStatus.PAUSED } }
            restored.resume()
            val complete = withTimeout(3_000) { restored.state.first { it[track.id]?.status == DownloadStatus.COMPLETE } }.getValue(track.id)
            assertContentEquals(audio, Files.readAllBytes(Path.of(complete.path!!)))
        } finally {
            offline?.closeAndJoin(); body.cancel(CancellationException("Test complete"))
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun cachedAudioCanBeEvictedWhileTheServerConnectionIsUnavailable() = runBlocking {
        val directory = Files.createTempDirectory("disconnected-cache")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val song = client.songs().first()
            val track = library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            val connected = AtomicBoolean(true)
            val worker = OfflineMusic(store, library, { client.takeIf { connected.get() } },
                directory.resolve("audio").toString(), Playlists(store))
            offline = worker
            worker.request(listOf(track.id), owner = null)
            withTimeout(3_000) { worker.state.first { it[track.id]?.status == DownloadStatus.COMPLETE } }
            connected.set(false)
            MusicPreferences(store).update { it.copy(cacheBytes = 0) }
            withTimeout(3_000) { worker.state.first { it.isEmpty() } }
            assertEquals(null, SourceResolver.resolve(library.findTrack(track.id)!!.sources, online = false))
        } finally { offline?.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun reducingTheCacheBudgetAutomaticallyEvictsIdleAudioAndKeepsThePlayingTrack() = runBlocking {
        val directory = Files.createTempDirectory("bounded-offline-cache")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val tracks = client.songs().map { song ->
                library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            }
            val playing = tracks.single { it.metadata.title == "Northern Light" }
            val worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store))
            offline = worker
            worker.useTrack(playing.id)
            worker.request(tracks.map { it.id }, owner = null)
            val downloaded = withTimeout(5_000) { worker.state.first { state ->
                tracks.all { state[it.id]?.status == DownloadStatus.COMPLETE }
            } }
            MusicPreferences(store).update { it.copy(cacheBytes = 0) }
            withTimeout(3_000) { worker.state.first { it.keys == setOf(playing.id) } }
            assertEquals(listOf("Northern Light"), library.tracks().filter {
                SourceResolver.resolve(it.sources, online = false) != null
            }.map { it.metadata.title })
            assertEquals(false, Files.exists(Path.of(downloaded.getValue(tracks.first { it.id != playing.id }.id).path!!)))
        } finally { offline?.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun automaticPlaylistDownloadsFollowEditsWithoutAManualSync() = runBlocking {
        val directory = Files.createTempDirectory("automatic-playlist")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val library = MusicLibrary(store)
            val tracks = client.songs().map { song ->
                library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            }.associateBy { it.metadata.title }
            val playlists = Playlists(store)
            val playlist = playlists.create("Evening", listOf(tracks.getValue("Northern Light").id))
            playlists.setAutoDownload(playlist.id, true)
            val worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), playlists)
            offline = worker
            withTimeout(3_000) { worker.state.first { it[tracks.getValue("Northern Light").id]?.status == DownloadStatus.COMPLETE } }
            playlists.replaceTracks(playlist.id, listOf(tracks.getValue("Long Way Home").id))
            withTimeout(3_000) { worker.state.first { it[tracks.getValue("Long Way Home").id]?.status == DownloadStatus.COMPLETE } }
            worker.enforceCacheBudget(0)
            assertEquals(listOf("Long Way Home"), library.tracks().filter {
                SourceResolver.resolve(it.sources, online = false) != null
            }.map { it.metadata.title })
        } finally { offline?.closeAndJoin(); store.close(); http.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aDownloadRequestedDuringAnOutageAutomaticallyRecoversAfterAppRestart() = runBlocking {
        val directory = Files.createTempDirectory("offline-recovery")
        val database = directory.resolve("library.db").toString()
        val http = HttpClient(OkHttp)
        val failedHttp = HttpClient(MockEngine { respond("Unavailable", HttpStatusCode.ServiceUnavailable) })
        val address = System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533"
        val credentials = ServerCredentials("fixture", "fixture-password")
        var store = openMusicStore(database)
        var offline: OfflineMusic? = null
        try {
            val client = NavidromeClient(http, address, credentials)
            val song = client.songs().first { it.metadata.title == "Northern Light" }
            val library = MusicLibrary(store)
            val track = library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            val unavailable = NavidromeClient(failedHttp, address, credentials)
            val before = OfflineMusic(store, library, { unavailable }, directory.resolve("audio").toString(), Playlists(store))
            offline = before
            before.request(listOf(track.id))
            withTimeout(3_000) { before.state.first { it[track.id]?.status == DownloadStatus.FAILED } }
            before.closeAndJoin(); store.close()
            store = openMusicStore(database)
            val restoredLibrary = MusicLibrary(store)
            val restored = OfflineMusic(store, restoredLibrary, { client }, directory.resolve("audio").toString(), Playlists(store))
            offline = restored
            val complete = withTimeout(5_000) { restored.state.first { it[track.id]?.status == DownloadStatus.COMPLETE } }.getValue(track.id)
            restored.enforceCacheBudget(0)
            assertEquals(SourceKind.DOWNLOAD, SourceResolver.resolve(restoredLibrary.findTrack(track.id)!!.sources, online = false)?.kind)
            val original = Path.of(System.getProperty("musicplayer.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            assertContentEquals(Files.readAllBytes(original), Files.readAllBytes(Path.of(complete.path!!)))
        } finally { offline?.closeAndJoin(); store.close(); http.close(); failedHttp.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun songsLinkedToADeviceFileDropTheirDownloadsButKeepTheirProtection() = runBlocking {
        val directory = Files.createTempDirectory("device-file-downloads")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val audio = Files.createDirectories(directory.resolve("audio"))
        val library = MusicLibrary(store)
        val offline = OfflineMusic(store, library, { error("Unlock your credential store") }, audio.toString(), Playlists(store),
            sourceAccessible = { Files.exists(Path.of(it)) }, scheduleWork = {})
        try {
            val downloaded = library.importTrack(TrackMetadata("Downloaded"), TrackSource("server:fixture:downloaded", SourceKind.SERVER, "downloaded"))
            val copy = Files.write(audio.resolve("downloaded.mp3"), byteArrayOf(1, 2, 3))
            store.put("download:${downloaded.id}", "downloads", DownloadRecord(downloaded.id, DownloadStatus.COMPLETE, copy.toString(), 3, 3))
            library.importTrack(downloaded.metadata, TrackSource("download:${downloaded.id}", SourceKind.DOWNLOAD, copy.toString()), linkTo = downloaded.id)
            AudioDownloads(store, library, null, audio.toString()).pin(downloaded.id, "manual")
            val requested = library.importTrack(TrackMetadata("Requested"), TrackSource("server:fixture:requested", SourceKind.SERVER, "requested"))
            offline.request(listOf(requested.id))
            for (track in listOf(downloaded, requested)) {
                val file = Files.write(directory.resolve("${track.metadata.title}.mp3"), byteArrayOf(1, 2, 3)).toString()
                val local = library.importTrack(track.metadata, TrackSource("local:$file", SourceKind.LOCAL, file))
                library.linkTracks(track.id, local.id)
            }
            offline.runPending()
            assertEquals(false, Files.exists(copy))
            assertEquals(emptyMap(), offline.state.value)
            assertEquals(null, store.database.records().read("request:${requested.id}"))
            assertEquals(true, store.database.records().read("pins:${downloaded.id}") != null)
            assertEquals(setOf(SourceKind.LOCAL, SourceKind.SERVER), library.findTrack(downloaded.id)!!.sources.map { it.kind }.toSet())
        } finally { offline.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
