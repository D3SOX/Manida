package me.d3sox.nevumi.library

import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LibraryRestartTest {
    @Test
    fun reopeningAnUnchangedLibraryUsesTheIndexWithoutStartingAScan() = runBlocking {
        val directory = Files.createTempDirectory("cached-library-restart")
        val path = directory.resolve("music.db").toString()
        var store = openMusicStore(path)
        val music = Files.createDirectory(directory.resolve("music"))
        val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        Files.copy(fixture, music.resolve("song.flac"))
        val song = LocalFiles(MusicLibrary(store)).scan(music.toString()).single()
        MusicLibrary(store).setFavorite(song.id, true)
        store.close(); store = openMusicStore(path)
        val library = MusicLibrary(store)
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = WatchingLocalFiles(library))
        try {
            val rescanned = withTimeoutOrNull(1_500) { player.state.first { it.localScan != null || it.lastLocalScanCount != null } }
            assertNull(rescanned, "An unchanged cached library must not rescan when the app opens")
            assertEquals(song.id, player.state.value.tracks.single().id)
            assertTrue(player.state.value.tracks.single().favorite)
        } finally { player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
    @Test
    fun filesChangedWhileTheAppWasClosedAreIndexedOnReopening() = runBlocking {
        val directory = Files.createTempDirectory("changed-library-restart")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val music = Files.createDirectory(directory.resolve("music"))
        val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        val first = Files.copy(fixture, music.resolve("song.flac"))
        val library = MusicLibrary(store)
        val song = LocalFiles(library).scan(music.toString()).single()
        Files.delete(first)
        val second = Files.copy(fixture, music.resolve("new.flac"))
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = WatchingLocalFiles(library))
        try {
            withTimeout(3_000) { player.state.first { state ->
                state.tracks.any { track -> track.sources.any { it.location == second.toString() && it.available } } &&
                    state.tracks.singleOrNull { it.id == song.id }?.sources?.single()?.available == false
            } }
            Unit
        } finally { player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

}
