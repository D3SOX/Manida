package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.playlists.Playlists
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PlayingOriginUiTest {
    @Test fun playingFromOpensTheAlbumOrPlaylistAtTheCurrentTrack() {
        val directory = Files.createTempDirectory("playing-origin-ui")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val audio = Path.of(System.getProperty("musicplayer.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val songs = runBlocking { (0 until 30).map { index ->
            library.importTrack(TrackMetadata("Song %02d".format(index), "Test artist", "Test album", trackNumber = index + 1, durationMs = 3_000),
                TrackSource("source:$index", SourceKind.LOCAL, audio))
        }.also { playlists.create("Night mix", it.map { song -> song.id }) } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                for ((page, title) in listOf("Albums" to "Test album", "Playlists" to "Night mix")) {
                    openLibraryPage(page); onNodeWithText(title).performClick()
                    onNode(hasScrollAction()).performScrollToIndex(26)
                    onNodeWithTag("track-row:${songs[25].id}").performClick()
                    waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentTrackId == songs[25].id }
                    runBlocking { controller.pause().join() }
                    onNodeWithTag("mini-player").performClick()
                    onNodeWithContentDescription("Playing context").performClick()
                    onNodeWithTag("track-row:${songs[25].id}").assertIsDisplayed()
                    kotlin.test.assertEquals(page, controller.state.value.preferences.lastLibraryTab.label)
                    onNode(hasScrollAction()).performScrollToIndex(1)
                    onNodeWithTag("track-row:${songs[0].id}").assertIsDisplayed()
                    openLibraryPage("Recently added")
                    onNodeWithContentDescription("Settings").performClick()
                    onNodeWithContentDescription("Back").performClick()
                    waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.lastLibraryTab == LibraryTab.RECENT }
                    val recent = (hasText("Recently added") or hasContentDescription("Recently added")) and hasClickAction()
                    if (onAllNodes(recent).fetchSemanticsNodes().isNotEmpty()) onNode(recent).assertIsSelected()
                    else onNodeWithText("More").assertIsSelected()
                    openLibraryPage(page)
                    if (onAllNodesWithTag("track-row:${songs[0].id}").fetchSemanticsNodes().isEmpty() &&
                        onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()) onNodeWithText(title).performClick()
                    onNodeWithTag("track-row:${songs[0].id}").assertIsDisplayed()
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
