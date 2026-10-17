package io.github.d3sox.musicplayer.downloads

import io.github.d3sox.musicplayer.library.SourceKind
import io.github.d3sox.musicplayer.library.Track
import io.github.d3sox.musicplayer.playlists.Playlist

data class PlaylistDownloadProgress(val playlist: Playlist, val downloaded: Int, val total: Int, val fraction: Float) {
    val complete get() = downloaded >= total
}

// Counts the songs of each automatically downloaded playlist that can play offline.
// Songs without a server copy cannot be downloaded, and removed songs stay excluded.
fun playlistDownloadProgress(playlists: List<Playlist>, tracks: List<Track>,
    downloads: Map<String, DownloadRecord>, excluded: Set<String> = emptySet()): List<PlaylistDownloadProgress> {
    val byId = tracks.associateBy { it.id }
    return playlists.filter { it.autoDownload }.map { playlist ->
        var downloaded = 0
        var partial = 0f
        val total = playlist.trackIds.distinct().count { id ->
            val track = byId[id] ?: return@count false
            val record = downloads[id]
            when {
                // An indexed local file can be gone; synchronization then downloads a replacement.
                record?.status == DownloadStatus.DOWNLOADING -> {
                    if (record.sizeBytes > 0) partial += (record.bytesDownloaded.toFloat() / record.sizeBytes).coerceIn(0f, 1f)
                    true
                }
                record?.status == DownloadStatus.COMPLETE || track.sources.any { it.kind == SourceKind.LOCAL && it.available } -> {
                    downloaded++; true
                }
                else -> id !in excluded && track.sources.any { it.kind == SourceKind.SERVER && it.available }
            }
        }
        PlaylistDownloadProgress(playlist, downloaded, total,
            if (total == 0) 1f else ((downloaded + partial) / total).coerceIn(0f, 1f))
    }
}
