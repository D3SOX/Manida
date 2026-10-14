package me.d3sox.manida

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.settings.MusicPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCrossfadeSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun crossfadeSettingsReachTheAudioServiceAndRemainSelectedAfterActivityRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val preferences = MusicPreferences(app.store)
        val original = runBlocking { preferences.load() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                runBlocking { app.controller.reload().join() }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Playback").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Crossfade").performScrollTo().performClick()
                compose.onNodeWithText("4 seconds").performScrollTo().performClick()
                compose.waitUntil(timeoutMillis = 5_000) { app.controller.state.value.playback.crossfadeDurationMs == 4_000L }
                assertEquals(4_000L, runBlocking { preferences.load() }.crossfadeDurationMs)
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                scenario.recreate()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Playback").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Crossfade").performScrollTo().assertTextContains("4 seconds")
                compose.onNodeWithContentDescription("Crossfade").performClick()
                compose.onNodeWithText("Off").performScrollTo().performClick()
                compose.waitUntil(timeoutMillis = 5_000) { app.controller.state.value.playback.crossfadeDurationMs == 0L }
            }
        } finally { runBlocking { app.controller.updatePreferences { original }.join() } }
    }
}
