package me.d3sox.manida

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.history.ListeningHistory
import io.github.d3sox.musicplayer.library.MusicLibrary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidListeningHistoryUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun completingASongShowsItsHistoryAndMostPlayedStatisticsOnThePhone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val resolver = app.contentResolver
        val audio = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "history-${UUID.randomUUID()}.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Manida-history-${UUID.randomUUID()}")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        try {
            instrumentation.context.assets.open("fixture.flac").use { input -> requireNotNull(resolver.openOutputStream(audio)).use { input.copyTo(it) } }
            resolver.update(audio, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = app.controller
                compose.waitUntil(5_000) { !controller.state.value.loading }
                runBlocking { controller.importSource(audio.toString()).join() }
                val track = controller.state.value.tracks.single { song -> song.sources.any { it.location == audio.toString() } }
                compose.onNodeWithText(track.metadata.title).performClick()
                compose.waitUntil(10_000) { controller.state.value.playback.ended && controller.state.value.listens.singleOrNull()?.ended == true }
                compose.openLibraryPage("History")
                compose.onNodeWithTag("listening-history").assertIsDisplayed()
                compose.onNodeWithText("Played", substring = true).assertIsDisplayed()
                compose.openLibraryPage("Statistics")
                compose.onNodeWithText("1 play").assertIsDisplayed()
                compose.onNodeWithText("Most played").performScrollTo().assertIsDisplayed()
                compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("statistics-most-played")))
                    .performScrollToNode(hasTestTag("track-row:${track.id}"))
                compose.onNode(hasText(track.metadata.title) and hasAnyAncestor(hasTestTag("statistics-most-played"))).assertIsDisplayed()
                assertEquals(1, runBlocking { ListeningHistory(app.store, MusicLibrary(app.store)).statistics().getValue(track.id).playCount })
                compose.onNodeWithContentDescription("Statistics period").performScrollTo().performClick()
                compose.onNodeWithText("Last 7 days").performClick()
                compose.onNodeWithText("1 play").assertIsDisplayed()
                for ((grouping, name) in listOf("Artists" to track.metadata.artist, "Albums" to track.metadata.album,
                    "Genres" to track.metadata.genres.first())) {
                    compose.onNodeWithContentDescription("Statistics grouping").performScrollTo().performClick()
                    compose.onNode(hasText(grouping) and hasAnyAncestor(isPopup())).performClick()
                    compose.onNodeWithTag("statistics-ranking").performScrollToNode(hasText(name))
                    compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("statistics-ranking"))).performClick()
                    compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("statistics-most-played")))
                        .performScrollToNode(hasTestTag("track-row:${track.id}"))
                    compose.onNode(hasText(track.metadata.title) and hasAnyAncestor(hasTestTag("statistics-most-played"))).assertIsDisplayed()
                    compose.onNodeWithContentDescription("Back").performClick()
                }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                compose.onNodeWithText("Clear listening history").performScrollTo().performClick()
                compose.onNodeWithText("Cancel").performClick()
                assertEquals(1, controller.state.value.listens.size)
                compose.onNodeWithText("Clear listening history").performScrollTo().performClick()
                compose.onNodeWithText("Clear history").performClick()
                compose.waitUntil(3_000) { controller.state.value.listens.isEmpty() }
                compose.onNodeWithContentDescription("Record listening history").performScrollTo().performClick()
                compose.onNode(hasText("Off") and hasAnyAncestor(isPopup())).performClick()
                compose.waitUntil(3_000) { !controller.state.value.preferences.recordListeningHistory }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                runBlocking { controller.playTrack(track.id).join() }
                compose.waitUntil(10_000) { controller.state.value.playback.ended }
                assertEquals(emptyList<io.github.d3sox.musicplayer.history.Listen>(), controller.state.value.listens)
                compose.openLibraryPage("Recommendations")
                compose.onNodeWithText("Play some music to discover more from your library.").assertIsDisplayed()
            }
        } finally { resolver.delete(audio, null, null) }
    }
}
