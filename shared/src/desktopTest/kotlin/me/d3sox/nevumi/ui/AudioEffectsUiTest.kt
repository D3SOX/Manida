package me.d3sox.nevumi.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AudioEffectsUiTest {
    @Test
    fun equalizerPreampAndMonoCanBeChangedAndRestoreAfterRestart() {
        val directory = Files.createTempDirectory("nevumi-audio-settings")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        fun controller() = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = MusicPreferences(store))
        var player = controller()
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithText("Audio effects").performScrollTo().performClick()
                onNodeWithContentDescription("Enable equalizer").performClick()
                onNodeWithContentDescription("Preamp").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(-6f) }
                onNodeWithContentDescription("Equalizer gain at 1 kHz").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
                onNodeWithContentDescription("Mono audio").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.playback.audioEffects.mono }
                val effects = runBlocking { MusicPreferences(store).load() }.audioEffects
                assertEquals(true, effects.enabled)
                assertEquals(-6.0, effects.preampDb)
                assertEquals(6.0, effects.bands.single { it.frequencyHz == 1_000.0 }.gainDb)
                assertEquals(effects, player.state.value.playback.audioEffects)
            }
            runBlocking { player.closeAndJoin() }; store.close()
            store = openMusicStore(database)
            player = controller()
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading && player.state.value.playback.audioEffects.mono }
                assertEquals(runBlocking { MusicPreferences(store).load() }.audioEffects, player.state.value.playback.audioEffects)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithText("Audio effects").performScrollTo().performClick()
                onNodeWithContentDescription("Enable equalizer").assertIsOn()
                onNodeWithContentDescription("Mono audio").performScrollTo().assertIsOn()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
