package io.github.d3sox.musicplayer.downloads

import io.github.d3sox.musicplayer.library.SourceKind
import io.github.d3sox.musicplayer.library.Track
import io.github.d3sox.musicplayer.library.TrackMetadata
import io.github.d3sox.musicplayer.library.TrackSource
import io.github.d3sox.musicplayer.playlists.Playlist
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistDownloadProgressTest {
    @Test
    fun countsOfflineSongsAndTheActiveTransferOfAutomaticPlaylists() {
        fun track(id: String, vararg kinds: SourceKind) = Track(id, TrackMetadata(id), kinds.map { TrackSource("$it:$id", it, id) })
        val tracks = listOf(track("done", SourceKind.SERVER), track("local", SourceKind.LOCAL), track("active", SourceKind.SERVER),
            track("queued", SourceKind.SERVER), track("unreachable"))
        val downloads = mapOf(
            "done" to DownloadRecord("done", DownloadStatus.COMPLETE),
            "active" to DownloadRecord("active", DownloadStatus.DOWNLOADING, bytesDownloaded = 50, sizeBytes = 100))
        val playlists = listOf(Playlist("auto", "Road trip", tracks.map { it.id }, autoDownload = true),
            Playlist("manual", "Ignored", tracks.map { it.id }))

        val progress = playlistDownloadProgress(playlists, tracks, downloads).single()

        assertEquals("Road trip", progress.playlist.name)
        assertEquals(2, progress.downloaded)
        assertEquals(4, progress.total)
        assertEquals(2.5f / 4, progress.fraction)
    }
}
