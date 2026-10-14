package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.playback.CrossfadePlaybackEngine
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.settings.MusicPreferences
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CrossfadeSettingsUiTest {
    @Test
    fun crossfadeCanBeEnabledFromSettingsAndRestoresAfterRestart() {
        val directory = Files.createTempDirectory("manida-crossfade-settings")
        val database = directory.resolve("music.db").toString()
        var store = openMusicStore(database)
        fun controller() = MusicController(MusicLibrary(store), PlaybackQueue(store),
            CrossfadePlaybackEngine { MpvPlaybackEngine("null") }, preferences = MusicPreferences(store))
        var player = controller()
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithContentDescription("Crossfade").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
                waitUntil(timeoutMillis = 3_000) { player.state.value.playback.crossfadeDurationMs == 4_000L }
                assertEquals(4_000L, runBlocking { MusicPreferences(store).load() }.crossfadeDurationMs)
            }
            runBlocking { player.closeAndJoin() }; store.close()
            store = openMusicStore(database)
            player = controller()
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading && player.state.value.playback.crossfadeDurationMs == 4_000L }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithContentDescription("Crossfade").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
                waitUntil(timeoutMillis = 3_000) { player.state.value.playback.crossfadeDurationMs == 0L }
                assertEquals(0L, runBlocking { MusicPreferences(store).load() }.crossfadeDurationMs)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
