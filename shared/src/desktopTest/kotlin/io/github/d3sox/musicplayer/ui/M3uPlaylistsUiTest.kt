package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.playlists.*
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class M3uPlaylistsUiTest {
    @Test
    fun aPlaylistFileCanBeImportedWithMissingEntriesReviewedAndExportedAgain() {
        val directory = Files.createTempDirectory("m3u-file-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val song = runBlocking { library.importTrack(TrackMetadata("Northern Light"),
            TrackSource("local:song", SourceKind.LOCAL, "/music/song.flac")) }
        val input = directory.resolve("Evening.m3u8")
        Files.writeString(input, "#EXTM3U\nsong.flac\nmissing.flac\n")
        val exported = directory.resolve("exported.m3u8")
        val picker = object : PlaylistFiles {
            override suspend fun open() = PlaylistDocument("Evening", Files.readString(input), "/music")
            override suspend fun save(name: String, text: String): Boolean {
                assertEquals("Evening.m3u8", name)
                Files.writeString(exported, text)
                return true
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, playlistFiles = picker) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                openLibraryPage("Playlists")
                onNodeWithContentDescription("Import M3U").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.size == 1 }
                onNodeWithText("Unresolved entries").assertIsDisplayed()
                onNodeWithText("missing.flac").assertIsDisplayed()
                onNodeWithText("Done").performClick()
                onNodeWithText("Evening").performClick()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Export M3U").performClick()
                waitUntil(timeoutMillis = 3_000) { Files.exists(exported) }
                assertEquals(listOf(song.id), runBlocking { M3uPlaylists(library, playlists).import("Round trip", Files.readString(exported)).playlist.trackIds })
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
