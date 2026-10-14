package me.d3sox.manida

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.activity.BackEventCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidPlayerCollapseTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun downwardSwipesRestoreTheMiniPlayerContentsAndBottomNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        fun capture(name: String) {
            val output = instrumentation.uiAutomation.takeScreenshot()
            File(app.filesDir, "$name.png").outputStream().use { output.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            output.recycle()
        }
        val audio = File(app.cacheDir, "collapse-fixture.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) { !app.controller.state.value.loading }
                val song = runBlocking { app.controller.library.importTrack(TrackMetadata("Collapse fixture"),
                    TrackSource("collapse-fixture", SourceKind.LOCAL, audio.path)) }
                runBlocking {
                    app.controller.reload().join()
                    app.controller.playTrack(song.id).join()
                    app.controller.pause().join()
                }
                compose.waitUntil(5_000) { app.controller.state.value.playback.durationMs == 3_000L }
                val entry = app.controller.state.value.queue.currentEntryId
                val artwork = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val navigation = compose.onNodeWithTag("library-navigation").fetchSemanticsNode().boundsInRoot
                try {
                    compose.onNodeWithTag("mini-player").performClick()
                    compose.waitForIdle()
                    scenario.onActivity {
                        it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    }
                    compose.waitForIdle()
                    scenario.onActivity {
                        it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 800f, .9993f, BackEventCompat.EDGE_LEFT))
                    }
                    compose.waitForIdle()
                    compose.onNodeWithTag("player-artwork", useUnmergedTree = true).assertIsDisplayed()
                    compose.onNodeWithContentDescription("Play").assertIsDisplayed()
                    compose.onNodeWithTag("library-navigation").assertIsDisplayed()
                    capture("collapse-near-end")
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    for (index in 0 until 40) {
                        compose.onNodeWithTag("mini-player").performClick()
                        compose.onNodeWithTag("now-playing").performTouchInput {
                            down(center.copy(y = 64f))
                            moveTo(center.copy(y = height * (.5f + (index % 20) * .01f)), 250L + index * 31)
                            if (index % 2 == 0) up() else cancel()
                        }
                        compose.waitUntil(2_000) {
                            runCatching { compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width == artwork.width }.getOrDefault(false)
                        }
                        compose.onNodeWithTag("library-navigation").assertIsDisplayed()
                    }
                    compose.onNodeWithTag("mini-player").performClick()
                    compose.onNodeWithTag("now-playing").performTouchInput {
                        down(center.copy(y = 64f))
                        moveTo(center.copy(y = height * .7f), 400)
                        cancel()
                    }
                    compose.waitUntil(2_000) {
                        runCatching { compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width == artwork.width }.getOrDefault(false)
                    }
                    compose.onNodeWithContentDescription("Mini-player artwork: Collapse fixture", useUnmergedTree = true).assertIsDisplayed()
                    compose.onNodeWithContentDescription("Play").assertIsDisplayed()
                    compose.onNodeWithTag("library-navigation").assertIsDisplayed()
                    assertEquals(artwork, compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
                    assertEquals(navigation, compose.onNodeWithTag("library-navigation").fetchSemanticsNode().boundsInRoot)
                    assertEquals(entry, app.controller.state.value.queue.currentEntryId)
                } catch (failure: Throwable) {
                    capture("collapse-failure")
                    throw failure
                }
            }
        } finally { runBlocking { app.controller.pause().join() }; audio.delete() }
    }
}
