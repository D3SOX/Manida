package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.sync.PairedDevices
import me.d3sox.nevumi.sync.SyncUnmatched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SongsToMatch(devices: PairedDevices, songs: List<SyncUnmatched>, tracks: List<Track>,
    artwork: Map<String, String>, dismiss: () -> Unit) {
    val peers by devices.state.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun key(song: SyncUnmatched) = "${song.peerDeviceId}:${song.track.key}"
    AnimatedNavigation(selected, null, canGoBack = selected != null, onBack = { selected = null; error = null },
        depth = { if (it == null) 0 else 1 }) { selection ->
        val song = songs.firstOrNull { key(it) == selection }
        SettingsScreen(onDismissRequest = { if (selection == null) dismiss() else { selected = null; error = null } },
            title = { Text(if (song == null) "Songs to match" else "Choose your copy") }, text = {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (song == null) {
                        Text("Choose your copy of songs shared by your other devices. Identical files and songs with matching identifiers are matched automatically.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (songs.isEmpty()) Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(AppIcon.CHECK.vector, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("All songs matched", style = MaterialTheme.typography.titleLarge)
                                Text("Favorites, playlists and saved queues can now use your copies of these songs.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        else LazyColumn(Modifier.weight(1f).testTag("songs-to-match"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(songs, key = { key(it) }) { shared ->
                                Card(onClick = { selected = key(shared); error = null }, modifier = Modifier.fillMaxWidth().semantics {
                                    contentDescription = "Match shared song: ${shared.track.metadata.title}"
                                }) {
                                    MatchingSong(shared.track.metadata, peer = peers.peers.firstOrNull { it.deviceId == shared.peerDeviceId }?.name ?: "Another device",
                                        trailing = { Icon(AppIcon.FORWARD.vector, null) })
                                }
                            }
                        }
                    } else {
                        var query by rememberSaveable(selection) { mutableStateOf("") }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            MatchingSong(song.track.metadata, peer = peers.peers.firstOrNull { it.deviceId == song.peerDeviceId }?.name ?: "Another device", emphasized = true)
                        }
                        OutlinedTextField(query, { query = it }, label = { Text("Find your song") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Find your song" })
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        val candidates = remember(query, tracks, song) {
                            tracks.filter { track -> listOf(track.metadata.title, track.metadata.artist, track.metadata.album, track.metadata.folder)
                                .any { it.contains(query.trim(), ignoreCase = true) } }
                                .sortedByDescending { track ->
                                    (if (track.metadata.title.equals(song.track.metadata.title, true)) 2 else 0) +
                                        (if (song.track.metadata.artist.isNotBlank() && track.metadata.artist.equals(song.track.metadata.artist, true)) 1 else 0)
                                }
                        }
                        Text(if (query.isBlank()) "Your library · ${candidates.size}" else countLabel(candidates.size, "match"), style = MaterialTheme.typography.titleSmall)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (candidates.isEmpty()) item { Text("No songs found. Try another title, artist or album.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(candidates, key = { it.id }) { track ->
                                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                                    Box(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                        busy = true; error = null
                                        scope.launch {
                                            try { devices.library.mapTrack(song.peerDeviceId, song.track.key, track.id); selected = null }
                                            catch (cancelled: CancellationException) { throw cancelled }
                                            catch (failure: Exception) { error = failure.message ?: "Could not match this song" }
                                            finally { busy = false }
                                        }
                                    }.semantics { contentDescription = "Choose this copy: ${track.metadata.title}" }) {
                                        MatchingSong(track.metadata, artwork = artwork[track.id], peer = track.metadata.folder,
                                            trailing = { Icon(AppIcon.LINK.vector, null, tint = MaterialTheme.colorScheme.primary) })
                                    }
                                }
                            }
                        }
                    }
                }
            })
    }
}

@Composable
private fun MatchingSong(metadata: TrackMetadata, artwork: String? = null, peer: String,
    trailing: (@Composable () -> Unit)? = null, emphasized: Boolean = false) {
    ListItem(colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent,
        headlineColor = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        supportingColor = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant),
        headlineContent = { Text(metadata.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOf(metadata.artist.ifBlank { "Unknown artist" }, metadata.album).filter { it.isNotBlank() }.joinToString(" · "),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (peer.isNotBlank()) Text(peer, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, leadingContent = { CollectionArtwork(artwork, metadata.title, AppIcon.MUSIC, Modifier.size(64.dp)) }, trailingContent = trailing)
}
