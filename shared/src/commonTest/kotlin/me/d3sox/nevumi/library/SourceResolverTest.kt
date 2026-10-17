package me.d3sox.nevumi.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceResolverTest {
    @Test
    fun availableLocalMusicIsPreferredOverAStream() {
        val sources = listOf(
            TrackSource("server", SourceKind.SERVER, "remote-song-id"),
            TrackSource("local", SourceKind.LOCAL, "file:///music/song.flac"),
        )

        assertEquals("local", SourceResolver.resolve(sources)?.id)
    }

    @Test
    fun aMissingLocalFileFallsBackToItsCompleteDownload() {
        val sources = listOf(
            TrackSource("local", SourceKind.LOCAL, "file:///music/missing.flac", available = false),
            TrackSource("download", SourceKind.DOWNLOAD, "file:///cache/song.flac"),
            TrackSource("server", SourceKind.SERVER, "remote-song-id"),
        )

        assertEquals("download", SourceResolver.resolve(sources)?.id)
    }

    @Test
    fun aStreamIsUnavailableInOfflineMode() {
        val sources = listOf(TrackSource("server", SourceKind.SERVER, "remote-song-id"))

        assertNull(SourceResolver.resolve(sources, online = false))
    }

    @Test
    fun theUsersConfiguredSourcePriorityIsRespected() {
        val sources = listOf(
            TrackSource("local", SourceKind.LOCAL, "file:///music/song.mp3"),
            TrackSource("download", SourceKind.DOWNLOAD, "file:///cache/song.flac"),
        )

        assertEquals("download", SourceResolver.resolve(
            sources,
            priority = listOf(SourceKind.DOWNLOAD, SourceKind.LOCAL, SourceKind.SERVER),
        )?.id)
    }
}
