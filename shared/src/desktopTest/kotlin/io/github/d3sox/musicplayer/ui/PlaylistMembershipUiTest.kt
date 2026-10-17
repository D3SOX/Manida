package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.playlists.Playlists
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlaylistMembershipUiTest {
    @Test
    fun nowPlayingCanManageMembershipBesideNextAndShowsWhenTheSongBelongsToAPlaylist() {
        val directory = Files.createTempDirectory("player-playlists")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val song = runBlocking { LocalFiles(library).scan(java.nio.file.Path.of(System.getProperty("musicplayer.testRoot"),
            ".test-environment", "music").toString()) }.first()
        runBlocking { playlists.create("Evening", emptyList()) }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                runBlocking { player.playTrack(song.id).join(); player.pause().join() }
                onNodeWithTag("mini-player").performClick()
                val button = onNodeWithContentDescription("Manage playlists")
                button.assertIsDisplayed().assertIsNotSelected()
                kotlin.test.assertTrue(button.fetchSemanticsNode().boundsInRoot.left >
                    onNodeWithContentDescription("Next track").fetchSemanticsNode().boundsInRoot.left)
                button.performClick()
                onNodeWithTag("playlist-membership-sheet").assertIsDisplayed()
                onNodeWithContentDescription("Playlist membership: Evening").assertIsOff().performClick()
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.playlists.single().trackIds == listOf(song.id) }
                button.assertIsSelected().performClick()
                onNodeWithContentDescription("Playlist membership: Evening").assertIsOn()
                onNodeWithTag("playlist-membership-sheet").performTouchInput {
                    swipe(topCenter.copy(y = top + 20f), bottomCenter, 300)
                }
                waitUntil(timeoutMillis = 1_000) { onAllNodesWithTag("playlist-membership-sheet").fetchSemanticsNodes().isEmpty() }
                assertEquals(false, player.state.value.playback.playing)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun bulkMembershipShowsMixedChecksAndArtworkAndOnlyAppliesConfirmedChanges() {
        val directory = Files.createTempDirectory("playlist-membership")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val songs = runBlocking {
            listOf("First song", "Second song").map { title ->
                library.importTrack(TrackMetadata(title), TrackSource(title, SourceKind.LOCAL, "/music/$title.flac"))
            }.also {
                playlists.create("Evening", listOf(it[0].id, it[0].id))
                playlists.create("Morning", emptyList())
                playlists.createSmart("All music", LibraryQuery())
            }
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Track options: First song").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Select all").performClick()
                fun manage() {
                    onNodeWithContentDescription("Selection actions").performClick()
                    onNodeWithText("Add to playlist").performClick()
                }
                fun membership(name: String) = onNodeWithContentDescription("Playlist membership: $name")
                manage()
                membership("Evening").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate))
                onNodeWithContentDescription("Playlist artwork: Evening", useUnmergedTree = true).assertIsDisplayed()
                membership("All music").assertIsNotEnabled().assertIsOn()
                membership("Evening").performClick()
                membership("Morning").performClick()
                onNodeWithText("Cancel").performClick()
                assertEquals(listOf(songs[0].id, songs[0].id), runBlocking { playlists.all().single { it.name == "Evening" }.trackIds })
                onNodeWithText("2 songs selected").assertIsDisplayed()
                manage()
                membership("Evening").performClick()
                membership("Morning").performClick()
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.playlists.single { it.name == "Morning" }.trackIds.size == 2 }
                assertEquals(listOf(songs[0].id, songs[0].id, songs[1].id), runBlocking { playlists.all().single { it.name == "Evening" }.trackIds })
                manage()
                membership("Evening").assertIsOn().performClick()
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.playlists.single { it.name == "Evening" }.trackIds.isEmpty() }
                assertEquals(songs.map { it.id }, runBlocking { playlists.all().single { it.name == "Morning" }.trackIds })
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun searchFiltersPlaylistsAndKeepsChoicesMadeBeforeFiltering() {
        val directory = Files.createTempDirectory("playlist-membership-search")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val song = runBlocking {
            library.importTrack(TrackMetadata("Song"), TrackSource("song", SourceKind.LOCAL, "/music/song.flac")).also {
                playlists.create("Evening", emptyList())
                playlists.create("Morning", emptyList())
            }
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Track options: Song").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Selection actions").performClick()
                onNodeWithText("Add to playlist").performClick()
                onNodeWithContentDescription("Playlist membership: Evening").performClick()
                onNodeWithText("Search playlists").performTextInput("morn")
                onAllNodesWithContentDescription("Playlist membership: Evening").assertCountEquals(0)
                onNodeWithContentDescription("Playlist membership: Morning").performClick()
                onNodeWithText("Search playlists").performTextReplacement("x")
                onNodeWithText("No playlists match \"x\".").assertIsDisplayed()
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.playlists.all { it.trackIds == listOf(song.id) } }
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
