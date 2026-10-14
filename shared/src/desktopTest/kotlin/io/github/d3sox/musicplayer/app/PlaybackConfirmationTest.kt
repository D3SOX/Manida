package io.github.d3sox.musicplayer.app

import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.queue.*
import io.github.d3sox.musicplayer.settings.MusicPreferences
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PlaybackConfirmationTest {
    @Test fun upNextProtectionIsOptionalAndOnlyGuardsStartingMusicOutsideTheQueue() = runBlocking {
        val directory = Files.createTempDirectory("up-next-confirmation")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        val audio = Path.of(System.getProperty("musicplayer.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val songs = (0..3).map { library.importTrack(TrackMetadata("Song $it"), TrackSource("local:$it", SourceKind.LOCAL, audio)) }
        val queue = PlaybackQueue(store)
        val player = MusicController(library, queue, MpvPlaybackEngine("null"), preferences = preferences)
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            assertFalse(player.state.value.preferences.confirmNextUpInterruption)
            player.playTrack(songs[0].id).join(); player.pause().join()
            player.enqueue(songs[1].id, QueueAction.PLAY_NEXT).join()
            player.playTrack(songs[2].id).join(); player.pause().join()
            assertNull(player.state.value.playbackConfirmation)
            assertEquals(songs[2].id, queue.snapshot().currentTrackId)
            player.updatePreferences { it.copy(confirmNextUpInterruption = true) }.join()
            player.enqueue(songs[1].id, QueueAction.PLAY_NEXT).join()
            val original = queue.snapshot()
            player.playTrack(songs[3].id).join()
            assertNotNull(player.state.value.playbackConfirmation)
            assertEquals(original.entries, queue.snapshot().entries)
            player.cancelPlaybackConfirmation()
            assertEquals(original.currentEntryId, queue.snapshot().currentEntryId)
            assertFalse(player.state.value.playback.playWhenReady)
            player.skip(1).join()
            assertNull(player.state.value.playbackConfirmation)
            assertEquals(songs[1].id, queue.snapshot().currentTrackId)
            player.selectQueueEntry(original.currentEntryId!!).join()
            assertNull(player.state.value.playbackConfirmation)
            player.playTrack(songs[3].id).join()
            assertNotNull(player.state.value.playbackConfirmation)
            player.acceptPlaybackConfirmation()
            withTimeout(3_000) { player.state.first { it.queue.currentTrackId == songs[3].id } }
            assertNull(player.state.value.playbackConfirmation)
            assertNull(player.state.value.error)
        } finally { player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
