package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.settings.MusicPreferences
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SettingsSearchUiTest {
    @Test
    fun searchingAcrossCategoriesOpensTheSettingAndBackRestoresTheCategory() {
        val directory = Files.createTempDirectory("settings-search")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val player = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextInput("crossfade")
                onNodeWithText("Crossfade").assertIsDisplayed().performClick()
                onNodeWithContentDescription("Crossfade").assertIsDisplayed()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextReplacement("black")
                onNodeWithText("AMOLED black").assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Crossfade").assertIsDisplayed()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextReplacement("black")
                onNodeWithText("AMOLED black").performClick()
                onNodeWithContentDescription("AMOLED black").assertIsDisplayed()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
