package me.d3sox.nevumi.ui

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playlists.Playlist
import kotlin.test.*

class LibrarySearchResultsTest {
    @Test fun resultsIncludeEveryTypeAndCollectionsRetainAllTheirTracks() {
        val tracks = listOf(
            Track("one", TrackMetadata("First", "Pulse", album = "Arc", genres = listOf("Ambient"), folder = "Library/Ambient", trackNumber = 2), emptyList()),
            Track("two", TrackMetadata("Second", "Other", album = "Arc", albumArtist = "Pulse", trackNumber = 1), emptyList()),
        )
        val playlist = Playlist("mix", "Ambient mix", listOf("two", "one", "two"))
        val album = searchLibrary(tracks, listOf(playlist), "arc").collections[SearchType.ALBUMS]!!.single()
        assertEquals(listOf("two", "one"), album.tracks.map { it.id })
        assertEquals("Pulse", album.subtitle)
        assertEquals(listOf("one"), searchLibrary(tracks, listOf(playlist), "first").songs.map { it.id })
        assertEquals("Pulse", searchLibrary(tracks, listOf(playlist), "pulse").collections[SearchType.ARTISTS]!!.single().title)
        val ambient = searchLibrary(tracks, listOf(playlist), "ambient")
        assertEquals(listOf("two", "one", "two"), ambient.collections[SearchType.PLAYLISTS]!!.single().tracks.map { it.id })
        assertEquals("Ambient", ambient.collections[SearchType.GENRES]!!.single().title)
        assertEquals("Library/Ambient", ambient.collections[SearchType.FOLDERS]!!.single().title)
        assertEquals(0, searchLibrary(tracks, listOf(playlist), "  ").count)
        assertEquals(0, searchLibrary(tracks, listOf(playlist), "missing").count)
    }
}
