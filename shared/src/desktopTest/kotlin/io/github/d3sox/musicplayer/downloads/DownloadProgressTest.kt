package io.github.d3sox.musicplayer.downloads

import io.github.d3sox.musicplayer.library.SourceKind
import io.github.d3sox.musicplayer.library.Track
import io.github.d3sox.musicplayer.library.TrackMetadata
import io.github.d3sox.musicplayer.library.TrackSource
import io.github.d3sox.musicplayer.playlists.Playlist
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadProgressTest {
    @Test
    fun countsOfflineSongsAndTheActiveTransferOfAutomaticPlaylists() {
        fun track(id: String, vararg kinds: SourceKind) = Track(id, TrackMetadata(id), kinds.map { TrackSource(if (it == SourceKind.SERVER) "server:home:$id" else "$it:$id", it, id) })
        val tracks = listOf(track("done", SourceKind.SERVER), track("local", SourceKind.LOCAL), track("active", SourceKind.SERVER, SourceKind.LOCAL),
            track("queued", SourceKind.SERVER), track("removed", SourceKind.SERVER), track("unreachable"), Track("elsewhere", TrackMetadata("elsewhere"), listOf(TrackSource("server:other:elsewhere", SourceKind.SERVER, "elsewhere"))),
            Track("deleted", TrackMetadata("deleted"), listOf(TrackSource("server:home:deleted", SourceKind.SERVER, "deleted", available = false))))
        val downloads = mapOf(
            "done" to DownloadRecord("done", DownloadStatus.COMPLETE),
            "active" to DownloadRecord("active", DownloadStatus.DOWNLOADING, bytesDownloaded = 50, sizeBytes = 100))
        val playlists = listOf(Playlist("auto", "Road trip", tracks.map { it.id }, autoDownload = true),
            Playlist("manual", "Ignored", tracks.map { it.id }))

        val progress = playlistDownloadProgress(playlists, tracks, downloads, "home", excluded = setOf("removed")).single()

        assertEquals("Road trip", progress.playlist.name)
        assertEquals(2, progress.downloaded)
        assertEquals(4, progress.total)
        assertEquals(2.5f / 4, progress.fraction)
        assertEquals(setOf("active", "queued"), progress.remaining)
    }

    @Test
    fun aBatchCollectsQueuedSongsFromAnySourceUntilTheQueueEmpties() {
        fun record(id: String, status: DownloadStatus, bytes: Long = 0) = id to DownloadRecord(id, status, bytesDownloaded = bytes, sizeBytes = 100)
        val playlist = PlaylistDownloadProgress(Playlist("auto", "Road trip", listOf("next"), autoDownload = true), 0, 1, 0f, setOf("next"))

        val started = nextDownloadBatch(emptySet(), mapOf(record("album-1", DownloadStatus.PENDING), record("album-2", DownloadStatus.PENDING)), listOf(playlist))
        assertEquals(setOf("album-1", "album-2", "next"), started.trackIds)

        val running = nextDownloadBatch(started.trackIds, mapOf(record("album-1", DownloadStatus.COMPLETE),
            record("album-2", DownloadStatus.DOWNLOADING, bytes = 50)), listOf(playlist))
        assertEquals(1, running.downloaded)
        assertEquals(1.5f / 3, running.fraction)
        assertEquals(1.5f / 3, nextDownloadBatch(running.trackIds, mapOf(record("album-1", DownloadStatus.COMPLETE),
            record("album-2", DownloadStatus.PAUSED, bytes = 50)), listOf(playlist)).fraction)

        // A cancelled song leaves the batch, and the batch ends once nothing is queued.
        val cancelled = nextDownloadBatch(running.trackIds, mapOf(record("album-1", DownloadStatus.COMPLETE)), listOf(playlist))
        assertEquals(setOf("album-1", "next"), cancelled.trackIds)
        assertEquals(DownloadBatch(), nextDownloadBatch(cancelled.trackIds, mapOf(record("album-1", DownloadStatus.COMPLETE),
            record("next", DownloadStatus.COMPLETE)), emptyList()))
    }
}
