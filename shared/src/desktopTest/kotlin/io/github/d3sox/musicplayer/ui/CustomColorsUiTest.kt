package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.settings.*
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class CustomColorsUiTest {
    @Test fun customColorsArePickedLiveAndSavedWithoutArtworkOverridingThem() {
        val directory = Files.createTempDirectory("manida-custom-colors")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                assertFalse(LibraryTab.ALBUM_ARTISTS in controller.state.value.preferences.libraryTabs)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Appearance").performScrollTo().performClick()
                onNodeWithContentDescription("Colors").performClick()
                onNodeWithText("Custom").performClick()
                onNodeWithContentDescription("Custom color: Green").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.customColor == 0xFF4CAF50 }
                assertEquals(ColorMode.CUSTOM, controller.state.value.preferences.colors)
                onNodeWithContentDescription("Custom color: Green").assertIsOn()
                onNodeWithContentDescription("Custom color: Purple").assertIsOff()
            }
            runBlocking { controller.closeAndJoin() }; store.close(); store = openMusicStore(database)
            val restored = runBlocking { MusicPreferences(store).load() }
            assertEquals(ColorMode.CUSTOM, restored.colors)
            assertEquals(0xFF4CAF50, restored.customColor)
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
