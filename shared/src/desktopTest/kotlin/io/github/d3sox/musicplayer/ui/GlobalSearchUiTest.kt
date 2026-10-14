package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.playlists.Playlists
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.settings.MusicPreferences
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class GlobalSearchUiTest {
    @Test fun searchFromHomeCoversTheLibraryAndLongPressUsesTheCollectionActions() {
        val directory = Files.createTempDirectory("global-search-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val queue = PlaybackQueue(store)
        val preferences = MusicPreferences(store)
        val audio = Path.of(System.getProperty("musicplayer.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val songs = runBlocking { (1..3).map { index -> library.importTrack(TrackMetadata("Arc song $index", "Pulse", album = "Arc album", trackNumber = index),
            TrackSource("source:$index", SourceKind.LOCAL, audio)) } }
        runBlocking {
            playlists.create("Arc playlist", songs.map { it.id })
            preferences.update { it.copy(startupTab = LibraryTab.HOME, libraryTabs = listOf(LibraryTab.HOME, LibraryTab.SONGS, LibraryTab.ALBUMS, LibraryTab.PLAYLISTS)) }
        }
        val controller = MusicController(library, queue, MpvPlaybackEngine("null"), playlists = playlists, preferences = preferences)
        try {
            runSkikoComposeUiTest(size = Size(1000f, 900f)) {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                onNodeWithTag("home-page").assertIsDisplayed()
                onNodeWithContentDescription("Search").performClick()
                onNodeWithTag("library-search-page").assertIsDisplayed()
                onNodeWithContentDescription("Search your library").performTextInput("Arc")
                onNodeWithTag("search-type:ALBUMS").performClick()
                onNodeWithContentDescription("Open album: Arc album").performTouchInput { longClick() }
                onNodeWithText("Add to queue").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.entries.size == 3 }
                assertEquals(songs.map { it.id }, runBlocking { queue.snapshot().entries.map { it.trackId } })
                onNodeWithContentDescription("Open album: Arc album").performClick()
                onNodeWithContentDescription("Track options: Arc song 3").assertIsDisplayed()
                onNodeWithContentDescription("Close search").performClick()
                onNodeWithTag("search-type:PLAYLISTS").performClick()
                onNodeWithTag("search-type:PLAYLISTS").assertIsSelected()
                System.getenv("MANIDA_UI_SNAPSHOTS")?.let { directory ->
                    val pixels = onNodeWithTag("app-window").captureToImage().toPixelMap()
                    val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                    val output = Path.of(directory).also(Files::createDirectories).resolve("global-search.png")
                    javax.imageio.ImageIO.write(image, "png", output.toFile())
                }
                onNodeWithContentDescription("Playlist options: Arc playlist").performClick()
                onNodeWithText("Edit playlist").performClick()
                onNodeWithContentDescription("Playlist title").performTextReplacement("Arc renamed")
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.playlists.single().name == "Arc renamed" }
                onNodeWithTag("search-type:SONGS").performClick()
                onNodeWithTag("track-row:${songs[0].id}").performTouchInput { longClick() }
                onNodeWithText("Play next").assertIsDisplayed()
                onNodeWithText("Select song").assertIsDisplayed()
                onNodeWithContentDescription("Close song actions").performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("track-actions").fetchSemanticsNodes().isEmpty() }
                onNodeWithContentDescription("Close search").performClick()
                onNodeWithTag("home-page").assertIsDisplayed()
                onNodeWithContentDescription("Home").assertIsSelected()
                assertNull(controller.state.value.error)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
