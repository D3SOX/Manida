package io.github.d3sox.musicplayer.history

import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.playback.PlaybackStatus
import io.github.d3sox.musicplayer.playlists.Playlists
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Clock

class ListeningQueriesTest {
    @Test
    fun timeBasedSmartPlaylistsRefreshWithoutAnotherPlayOrLibraryChange() = runBlocking {
        val directory = Files.createTempDirectory("aging-listening-rules")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val song = library.importTrack(TrackMetadata("Song", durationMs = 20_000),
                TrackSource("local", SourceKind.LOCAL, "/song.flac"))
            var elapsed = 0L
            val wall = java.util.concurrent.atomic.AtomicLong(1_800_000_000_000L)
            val history = ListeningHistory(store, library, wallClock = wall::get, elapsedClock = { elapsed })
            val playing = PlaybackStatus(song.id, playing = true, playbackId = "play", durationMs = 20_000)
            history.record(playing); elapsed = 10_000; history.record(playing.copy(playing = false))
            val playlists = Playlists(store, clock = wall::get, refreshEveryMs = 20)
            val recent = playlists.createSmart("Recently played", LibraryQuery(lastPlayedWithinDays = 7))
            val forgotten = playlists.createSmart("Rediscover", LibraryQuery(notPlayedWithinDays = 7))
            assertEquals(listOf(song.id), recent.trackIds)
            assertEquals(emptyList(), forgotten.trackIds)
            val updates = kotlinx.coroutines.channels.Channel<List<io.github.d3sox.musicplayer.playlists.Playlist>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val observing = launch { playlists.observe().collect { updates.send(it) } }
            try {
                assertEquals(listOf(song.id), withTimeout(3_000) { updates.receive() }.single { it.id == recent.id }.trackIds)
                wall.addAndGet(8 * 86_400_000L)
                val aged = withTimeout(3_000) {
                    var next = updates.receive()
                    while (next.single { it.id == recent.id }.trackIds.isNotEmpty()) next = updates.receive()
                    next
                }
                assertEquals(emptyList(), aged.single { it.id == recent.id }.trackIds)
                assertEquals(listOf(song.id), aged.single { it.id == forgotten.id }.trackIds)
                assertEquals(emptyList(), playlists.get(recent.id)!!.trackIds)
                assertEquals(listOf(song.id), playlists.get(forgotten.id)!!.trackIds)
            } finally { observing.cancelAndJoin(); updates.close() }
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playCountsAndLastPlayedRulesUpdateSmartPlaylistsAndSurviveRestart() = runBlocking {
        val directory = Files.createTempDirectory("listening-queries")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        try {
            val library = MusicLibrary(store)
            val songs = listOf("Old song", "Recent song", "Unplayed song").map { title ->
                library.importTrack(TrackMetadata(title, durationMs = 20_000),
                    TrackSource("local:$title", SourceKind.LOCAL, "/music/$title.flac"))
            }
            var elapsed = 0L
            var started = Clock.System.now().toEpochMilliseconds() - 10 * 86_400_000L
            val history = ListeningHistory(store, library, wallClock = { started }, elapsedClock = { elapsed })
            suspend fun play(song: Track, id: String) {
                val status = PlaybackStatus(song.id, playing = true, durationMs = 20_000, playbackId = id)
                history.record(status); elapsed += 10_000; history.record(status.copy(playing = false))
            }
            play(songs[0], "old")
            started = Clock.System.now().toEpochMilliseconds()
            play(songs[1], "recent-first")
            assertEquals(listOf(songs[1].id), library.tracks(LibraryQuery(lastPlayedWithinDays = 7)).map { it.id })
            assertEquals(listOf(songs[0].id, songs[2].id), library.tracks(LibraryQuery(notPlayedWithinDays = 7)).map { it.id })
            assertEquals(listOf(songs[2].id), library.tracks(LibraryQuery(playCountTo = 0)).map { it.id })
            val playlists = Playlists(store)
            val mostPlayed = playlists.createSmart("Most played", LibraryQuery(playCountFrom = 2, sort = LibrarySort.PLAY_COUNT))
            assertEquals(emptyList(), mostPlayed.trackIds)
            val changed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(3_000) { playlists.observe().first { it.single { p -> p.id == mostPlayed.id }.trackIds == listOf(songs[1].id) } }
            }
            play(songs[1], "recent-second")
            assertEquals(listOf(songs[1].id), changed.await().single().trackIds)
            assertEquals(listOf(songs[1].id, songs[0].id, songs[2].id),
                library.tracks(LibraryQuery(sort = LibrarySort.PLAY_COUNT)).map { it.id })
            assertEquals(listOf(songs[1].id, songs[0].id, songs[2].id),
                library.tracks(LibraryQuery(sort = LibrarySort.LAST_PLAYED)).map { it.id })
            assertFailsWith<IllegalArgumentException> { LibraryQuery(playCountFrom = -1) }
            assertFailsWith<IllegalArgumentException> { LibraryQuery(playCountFrom = 3, playCountTo = 2) }
            assertFailsWith<IllegalArgumentException> { LibraryQuery(lastPlayedWithinDays = 0) }
            store.close(); store = openMusicStore(database)
            assertEquals(listOf(songs[1].id), Playlists(store).get(mostPlayed.id)!!.trackIds)
            ListeningHistory(store, MusicLibrary(store)).clear()
            assertEquals(emptyList(), Playlists(store).get(mostPlayed.id)!!.trackIds)
            assertEquals(3, MusicLibrary(store).tracks(LibraryQuery(playCountTo = 0)).size)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
