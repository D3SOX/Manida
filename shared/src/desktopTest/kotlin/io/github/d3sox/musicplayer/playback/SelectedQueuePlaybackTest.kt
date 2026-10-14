package io.github.d3sox.musicplayer.playback

import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.queue.*
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SelectedQueuePlaybackTest {
    @Test fun switchingRestoresExactOccurrencesAndPositionsAndPreservesTransportState() = runBlocking {
        val directory = Files.createTempDirectory("selected-queue-playback")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("musicplayer.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val tracks = (1..3).map { library.importTrack(TrackMetadata("Song $it", durationMs = 3_000), TrackSource("source:$it", SourceKind.LOCAL, audio)) }
        val queue = PlaybackQueue(store)
        val player = MusicController(library, queue, MpvPlaybackEngine("null"))
        try {
            withTimeout(5_000) { player.state.first { it.libraryReady } }
            player.playTrack(tracks[0].id, listOf(tracks[0].id, tracks[1].id, tracks[0].id), 2).join()
            player.pause().join(); player.seek(1_200).join()
            withTimeout(5_000) { player.state.first { it.playback.positionMs in 1_150..1_250 } }
            player.saveQueue("Evening").join()
            val evening = queue.saved().single()
            val occurrence = queue.snapshot().currentEntryId
            player.enqueue(tracks[2].id, QueueAction.APPEND).join()
            assertEquals(4, queue.saved().single().snapshot.entries.size)
            player.playTrack(tracks[2].id, listOf(tracks[2].id)).join()
            player.pause().join(); player.seek(600).join()
            withTimeout(5_000) { player.state.first { it.playback.positionMs in 550..650 } }
            assertNull(queue.snapshot().selectedSavedQueueId)
            player.saveQueue("Morning").join()
            val morning = queue.saved().first { it.name == "Morning" }
            player.loadSavedQueue(evening.id).join()
            withTimeout(5_000) { player.state.first { it.playback.entryId == occurrence && it.playback.positionMs in 1_150..1_250 } }
            assertFalse(player.state.value.playback.playWhenReady)
            player.loadSavedQueue(morning.id).join()
            withTimeout(5_000) { player.state.first { it.playback.trackId == tracks[2].id && it.playback.positionMs in 550..650 } }
            player.play().join()
            player.loadSavedQueue(evening.id).join()
            withTimeout(5_000) { player.state.first { it.playback.entryId == occurrence && it.playback.playing } }
            assertTrue(player.state.value.playback.playWhenReady)
            player.pause().join()
            player.overwriteSavedQueue(morning.id).join()
            assertEquals(morning.id, queue.snapshot().selectedSavedQueueId)
            assertEquals(queue.snapshot().copy(selectedSavedQueueId = null), queue.saved().first { it.id == morning.id }.snapshot)
            assertNull(player.state.value.error)
        } finally { player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
