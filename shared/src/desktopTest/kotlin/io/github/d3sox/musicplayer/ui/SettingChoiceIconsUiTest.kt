package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.settings.*
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingChoiceIconsUiTest {
    @Test
    fun selectedSettingsHaveVisibleIconsIncludingLastOpenedPage() {
        val directory = Files.createTempDirectory("selected-setting-icons")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(themeMode = ThemeMode.DARK, amoledBlack = true) } }
        val player = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Library & gestures").performClick()
                fun assertLeadingIcon(label: String, value: String) {
                    val control = onNodeWithContentDescription(label)
                    val bounds = control.fetchSemanticsNode().boundsInRoot
                    val text = onNode(hasText(value) and hasAnyAncestor(hasContentDescription(label)), useUnmergedTree = true)
                        .fetchSemanticsNode().boundsInRoot
                    val width = (text.left - bounds.left - 2).toInt()
                    assertTrue(width >= 28, "The selected $label value needs room for its icon")
                    val image = control.captureToImage().toPixelMap()
                    val painted = (0 until width).sumOf { x -> (0 until image.height).count { y -> image[x, y] != Color.Black } }
                    assertTrue(painted > 12, "The selected $label icon must be painted")
                }
                assertLeadingIcon("On launch", "Last opened page")
                assertLeadingIcon("Swipe right", "Play next")
                onNodeWithContentDescription("On launch").performClick()
                onNodeWithText("Albums").performClick()
                assertLeadingIcon("On launch", "Albums")
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
