package io.github.d3sox.musicplayer.downloads

import io.github.d3sox.musicplayer.library.SourceKind
import io.github.d3sox.musicplayer.library.Track
import io.github.d3sox.musicplayer.playlists.Playlist

data class PlaylistDownloadProgress(val playlist: Playlist, val downloaded: Int, val total: Int, val fraction: Float,
    val remaining: Set<String> = emptySet()) {
    val complete get() = downloaded >= total
}

// Counts the songs of each automatically downloaded playlist that can play offline.
// Only songs on the connected server can be downloaded, and removed songs stay excluded.
fun playlistDownloadProgress(playlists: List<Playlist>, tracks: List<Track>,
    downloads: Map<String, DownloadRecord>, serverId: String?, excluded: Set<String> = emptySet()): List<PlaylistDownloadProgress> {
    val byId = tracks.associateBy { it.id }
    return playlists.filter { it.autoDownload }.map { playlist ->
        var downloaded = 0
        val remaining = mutableSetOf<String>()
        for (id in playlist.trackIds.distinct()) {
            val track = byId[id] ?: continue
            val record = downloads[id]
            when {
                // An indexed local file can be gone; synchronization then downloads a replacement.
                record?.status == DownloadStatus.DOWNLOADING -> remaining += id
                record?.status == DownloadStatus.COMPLETE || track.sources.any { it.kind == SourceKind.LOCAL && it.available } -> downloaded++
                id !in excluded && track.sources.any { it.kind == SourceKind.SERVER && it.available && it.id.startsWith("server:$serverId:") } ->
                    remaining += id
            }
        }
        val total = downloaded + remaining.size
        PlaylistDownloadProgress(playlist, downloaded, total,
            if (total == 0) 1f else ((downloaded + remaining.sumOf { partial(downloads[it]) }) / total).toFloat(), remaining)
    }
}

// Songs queued since the download queue was last empty, from any source: single songs, albums, artists, selections, and playlists.
data class DownloadBatch(val trackIds: Set<String> = emptySet(), val downloaded: Int = 0, val fraction: Float = 0f) {
    val total get() = trackIds.size
}

fun nextDownloadBatch(previous: Set<String>, downloads: Map<String, DownloadRecord>, playlists: List<PlaylistDownloadProgress>): DownloadBatch {
    val queued = downloads.values.filter { it.status in QUEUED }.map { it.trackId }.toSet() + playlists.flatMap { it.remaining }
    if (queued.isEmpty()) return DownloadBatch()
    // Cancelled songs leave the batch; finished and failed ones stay until the queue empties.
    val ids = (previous + queued).filterTo(mutableSetOf()) { it in queued || downloads[it] != null }
    val downloaded = ids.count { downloads[it]?.status == DownloadStatus.COMPLETE }
    return DownloadBatch(ids, downloaded, ((downloaded + ids.sumOf { partial(downloads[it]) }) / ids.size).toFloat().coerceIn(0f, 1f))
}

private val QUEUED = setOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)

// A paused transfer keeps its partial file and resumes from it.
private fun partial(record: DownloadRecord?): Double =
    if ((record?.status == DownloadStatus.DOWNLOADING || record?.status == DownloadStatus.PAUSED) && record.sizeBytes > 0)
        (record.bytesDownloaded.toDouble() / record.sizeBytes).coerceIn(0.0, 1.0) else 0.0
