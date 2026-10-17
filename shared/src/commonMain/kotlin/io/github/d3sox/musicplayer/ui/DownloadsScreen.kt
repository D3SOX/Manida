package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.d3sox.musicplayer.app.AppState
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.downloads.DownloadStatus
import io.github.d3sox.musicplayer.downloads.DownloadProblem
import io.github.d3sox.musicplayer.downloads.playlistDownloadProgress

@Composable
internal fun DownloadsScreen(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    val paused = state.preferences.downloadsPaused
    val playlists = playlistDownloadProgress(state.playlists, state.tracks, state.downloads, state.server?.serverId, state.excludedDownloads)
    val waiting = state.downloads.values.any { it.status != DownloadStatus.COMPLETE } || playlists.any { !it.complete }
    SettingsScreen(onDismissRequest = dismiss, title = { Text("Downloads") }, text = {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when {
                paused -> "Downloads paused"
                waiting && !state.downloadNetwork.connected -> "Waiting for a network"
                waiting && state.downloadNetwork.metered && !state.preferences.downloadOnMetered -> "Waiting for an unmetered network"
                else -> "${countLabel(state.downloads.count { it.value.status == DownloadStatus.COMPLETE }, "song")} downloaded"
            })
            TextButton(onClick = { controller.setDownloadsPaused(!paused) }) {
                Text(if (paused) "Resume downloads" else "Pause downloads")
            }
            if (state.downloads.values.any { it.status == DownloadStatus.FAILED })
                TextButton(onClick = controller::retryDownloads) { Text("Retry failed downloads") }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(playlists, key = { "playlist:${it.playlist.id}" }) { progress ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(progress.playlist.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (progress.complete) "${countLabel(progress.total, "song")} downloaded"
                            else "${progress.downloaded} of ${countLabel(progress.total, "song")} downloaded",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playlist download progress: ${progress.playlist.name}" })
                    }
                }
                if (playlists.isNotEmpty() && state.downloads.isNotEmpty()) item { HorizontalDivider() }
                items(state.downloads.values.sortedBy { record -> state.tracks.firstOrNull { it.id == record.trackId }?.metadata?.title },
                    key = { it.trackId }) { record ->
                    val title = state.tracks.firstOrNull { it.id == record.trackId }?.metadata?.title ?: "Unavailable song"
                    Column {
                        Text(title,
                            style = MaterialTheme.typography.titleMedium)
                        Text(when (record.status) {
                            DownloadStatus.COMPLETE -> "Downloaded"
                            DownloadStatus.DOWNLOADING -> "Downloading"
                            DownloadStatus.PENDING -> "Queued"
                            DownloadStatus.PAUSED -> "Paused"
                            DownloadStatus.FAILED -> if (record.problem == DownloadProblem.INSUFFICIENT_SPACE)
                                "Not enough storage — free space and retry" else "Failed — retry to continue"
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.status == DownloadStatus.DOWNLOADING) {
                            if (record.sizeBytes > 0) LinearProgressIndicator(progress = {
                                (record.bytesDownloaded.toFloat() / record.sizeBytes).coerceIn(0f, 1f)
                            }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Text("${record.bytesDownloaded} / ${record.sizeBytes} bytes", style = MaterialTheme.typography.labelSmall)
                        val removal = if (record.status == DownloadStatus.COMPLETE) "Remove download" else "Cancel download"
                        TextButton(onClick = { controller.removeDownload(record.trackId) },
                            modifier = Modifier.semantics { contentDescription = "$removal: $title" }) { Text(removal) }
                    }
                }
                if (state.downloads.isEmpty() && playlists.isEmpty()) item { Text("Download songs or enable automatic downloads on a playlist.") }
            }
        }
    }, confirmButton = { PageAction("Done", onClick = dismiss) })
}
