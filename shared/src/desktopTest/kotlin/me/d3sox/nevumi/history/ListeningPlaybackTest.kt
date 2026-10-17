package me.d3sox.nevumi.history

import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ListeningPlaybackTest {
    @Test
    fun repeatingTheSameQueueOccurrenceCreatesTwoListensAndTwoPlays() = runBlocking {
        val directory = Files.createTempDirectory("repeated-listening-history")
        val store = openMusicStore(directory.resolve("library.db").toString())
        var controller: MusicController? = null
        try {
            val library = MusicLibrary(store)
            val track = library.importTrack(TrackMetadata("Northern Light", durationMs = 3_000),
                TrackSource("local", SourceKind.LOCAL, Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                    "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()))
            val history = ListeningHistory(store, library)
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), listeningHistory = history)
            controller = player
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(track.id).join()
            player.setQueueRepeat(RepeatMode.ONE, playCount = 2).join()
            withTimeout(9_000) { player.state.first { it.playback.ended && it.listens.size == 2 && it.listens.all { event -> event.ended } } }
            player.closeAndJoin()
            val events = history.all()
            assertEquals(2, events.size)
            assertEquals(2, events.map { it.playbackId }.toSet().size)
            assertEquals(setOf(track.id), events.map { it.trackId }.toSet())
            assertEquals(2, history.statistics().getValue(track.id).playCount)
        } finally { controller?.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun completingARealSongRecordsOneListenAndItsStatisticsThroughTheAppController() = runBlocking {
        val directory = Files.createTempDirectory("real-listening-history")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var controller: MusicController? = null
        try {
            val library = MusicLibrary(store)
            val track = library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album", durationMs = 3_000),
                TrackSource("local", SourceKind.LOCAL, Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                    "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()))
            val history = ListeningHistory(store, library)
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), listeningHistory = history)
            controller = player
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(track.id).join()
            withTimeout(6_000) { player.state.first { it.playback.ended } }
            player.closeAndJoin()
            val event = history.all().single()
            assertEquals(track.id, event.trackId)
            // mpv's null sink finishes after queueing the final audio buffer.
            assertTrue(event.listenedMs in 2_000..3_500, "Recorded listen: $event")
            assertTrue(event.ended)
            assertEquals(1, history.statistics().getValue(track.id).playCount)
            assertEquals(listOf(event.id), player.state.value.listens.map { it.id })
            store.close(); store = openMusicStore(database)
            assertEquals(listOf(event), ListeningHistory(store, MusicLibrary(store)).all())
        } finally { controller?.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
