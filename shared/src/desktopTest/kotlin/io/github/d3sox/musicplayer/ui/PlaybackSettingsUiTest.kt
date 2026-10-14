package io.github.d3sox.musicplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.settings.MusicPreferences
import io.github.d3sox.musicplayer.storage.openMusicStore
import io.github.d3sox.musicplayer.visualizer.MilkDropSettings
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PlaybackSettingsUiTest {
    @Test fun quickControlsAdjustCrossfadePreciselyAndSaveGaplessAndVisualizerChoices() {
        val directory = Files.createTempDirectory("manida-playback-settings")
        val database = directory.resolve("music.db").toString()
        val store = openMusicStore(database)
        val preferences = MusicPreferences(store)
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        var openedEffects = false
        val visualizer = MilkDropSettings("Original/locked.milk", automatic = false, favorites = setOf("Original/locked.milk"),
            favoritesOnly = true, collection = "Original MilkDrop", category = "Waveform")
        try {
            runComposeUiTest {
                setContent { MaterialTheme { PlaybackQuickSettings(controller.state.collectAsState().value,
                    controller, { openedEffects = true }, {}) } }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                onNodeWithContentDescription("Increase Crossfade").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.preferences.crossfadeDurationMs == 1_000L }
                onNodeWithContentDescription("Decrease Crossfade").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.preferences.crossfadeDurationMs == 0L }
                onNodeWithContentDescription("Decrease Crossfade").assertIsNotEnabled()
                onNodeWithContentDescription("Gapless playback").performClick()
                waitUntil(timeoutMillis = 5_000) { !controller.state.value.preferences.gaplessPlayback }
                onNodeWithText("15 min").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.sleepTimerEndsAt != null }
                onNodeWithText("Cancel timer").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.sleepTimerEndsAt == null }
                onNodeWithText("Audio effects").performClick()
                assertTrue(openedEffects)
                runBlocking { controller.updatePreferences { it.copy(milkDrop = visualizer) }.join() }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close() }
        val restored = openMusicStore(database)
        try {
            val saved = runBlocking { MusicPreferences(restored).load() }
            assertFalse(saved.gaplessPlayback)
            assertEquals(visualizer, saved.milkDrop)
        } finally { restored.close(); directory.toFile().deleteRecursively() }
    }
}
