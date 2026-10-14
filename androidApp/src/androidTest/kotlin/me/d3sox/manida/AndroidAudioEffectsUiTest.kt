package me.d3sox.manida

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.playback.AndroidPlaybackEngine
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.settings.MusicPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAudioEffectsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun equalizerAndMonoApplyToTheServiceAndRestoreForANewController() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val preferences = MusicPreferences(app.store)
        val original = runBlocking { preferences.load() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { !app.controller.state.value.loading }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Playback").performScrollTo().performClick()
                compose.onNodeWithText("Audio effects").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Enable equalizer").performClick()
                compose.waitUntil(5_000) { app.controller.state.value.playback.audioEffects.enabled }
                compose.onNodeWithContentDescription("Preamp").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(-6f) }
                compose.onNodeWithContentDescription("Equalizer gain at 1 kHz").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
                compose.onNodeWithContentDescription("Mono audio").performScrollTo().performClick()
                compose.waitUntil(5_000) { app.controller.state.value.playback.audioEffects.mono }
                val effects = runBlocking { preferences.load() }.audioEffects
                assertEquals(-6.0, effects.preampDb, 0.001)
                assertEquals(6.0, effects.bands.single { it.frequencyHz == 1_000.0 }.gainDb, 0.001)
                assertEquals(effects, app.controller.state.value.playback.audioEffects)
                val restored = MusicController(MusicLibrary(app.store), PlaybackQueue(app.store),
                    AndroidPlaybackEngine(app, PlaybackService::class.java), preferences = preferences)
                try {
                    compose.waitUntil(5_000) { !restored.state.value.loading && restored.state.value.playback.audioEffects == effects }
                    assertEquals(effects, restored.state.value.preferences.audioEffects)
                } finally { runBlocking { restored.closeAndJoin() } }
            }
        } finally { runBlocking { app.controller.updatePreferences { original }.join() } }
    }
}
