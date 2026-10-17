package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import io.github.d3sox.musicplayer.playlists.PlaylistSort
import io.github.d3sox.musicplayer.library.Track
import io.github.d3sox.musicplayer.library.recentAt
import kotlin.random.Random
import androidx.compose.ui.unit.dp
import com.materialkolor.ktx.themeColor
import io.github.d3sox.musicplayer.app.AppState
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.playlists.Playlist
import io.github.d3sox.musicplayer.playlists.PLAYLIST_FOLDER_SEPARATOR
import io.github.d3sox.musicplayer.playlists.folder
import io.github.d3sox.musicplayer.playlists.playlistName
import io.github.d3sox.musicplayer.playlists.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

internal fun Playlist.artworkPath(state: AppState, custom: Boolean = true): String? = state.artwork["playlist:$id"].takeIf { custom }
    ?: state.artwork["server-playlist-cover:$id"]
    ?: trackIds.firstNotNullOfOrNull { state.artwork[it] }

@Composable
internal fun PlaylistHero(playlist: Playlist, state: AppState, controller: MusicController,
    orderedIds: List<String>, back: () -> Unit, edit: () -> Unit, delete: () -> Unit, export: () -> Unit, editRules: () -> Unit,
    publish: (() -> Unit)?, resolve: () -> Unit, busy: Boolean, rearrange: (() -> Unit)? = null,
    finishRearranging: (() -> Unit)? = null) {
    val path = playlist.artworkPath(state)
    val origin = LocalLibraryOrigin.current
    val bitmap = rememberArtwork(path, 128)
    val fallback = MaterialTheme.colorScheme.primary
    val color by produceState(fallback, bitmap) {
        value = if (bitmap == null) fallback else withContext(Dispatchers.Default) { bitmap.themeColor(fallback) }
    }
    val surface = MaterialTheme.colorScheme.surface
    var options by remember(playlist.id) { mutableStateOf(false) }
    var sortMenu by remember(playlist.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(color.copy(alpha = .4f), surface)))
        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            CollectionArtwork(path, playlist.name, AppIcon.QUEUE,
                Modifier.size(200.dp).align(Alignment.Center).semantics { contentDescription = "Playlist artwork: ${playlist.name}" })
            AppIconButton(AppIcon.BACK, "Back", Modifier.align(Alignment.TopStart), onClick = back)
        }
        Text(playlist.title, style = MaterialTheme.typography.headlineMedium)
        Text("${if (playlist.rule == null) "Playlist" else "Smart playlist"}${playlist.folder?.let { " in $it" }.orEmpty()} · ${countLabel(playlist.trackIds.size, "song")}",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (playlist.description.isNotBlank()) Text(playlist.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                if (finishRearranging != null) AppIconButton(AppIcon.CHECK, "Finish rearranging", onClick = finishRearranging)
                else AppIconButton(AppIcon.MORE, "Playlist options") { options = true }
                DropdownMenu(options, { options = false }) {
                    fun close(action: () -> Unit) { options = false; action() }
                    QueueActionMenuItems(orderedIds, controller) { options = false }
                    HorizontalDivider()
                    if (playlist.server?.readOnly != true) {
                        AppMenuItem({ Text("Edit playlist") }, { close(edit) }, leadingIcon = { Icon(AppIcon.EDIT.vector, null) })
                        AppMenuItem({ Text("Delete playlist") }, { close(delete) }, leadingIcon = { Icon(AppIcon.REMOVE.vector, null) })
                    }
                    AppMenuItem({ Text("Export M3U") }, { close(export) }, enabled = !busy, leadingIcon = { Icon(AppIcon.DOWNLOAD.vector, null) })
                    if (rearrange != null) AppMenuItem({ Text("Rearrange") }, { close(rearrange) }, leadingIcon = { Icon(AppIcon.DRAG.vector, null) })
                    if (playlist.rule != null) AppMenuItem({ Text("Edit rules") }, { close(editRules) }, leadingIcon = { Icon(AppIcon.EDIT.vector, null) })
                    if (publish != null) AppMenuItem({ Text("Publish to your music server") }, { close(publish) }, leadingIcon = { Icon(AppIcon.CLOUD.vector, null) })
                    if (playlist.conflict != null) AppMenuItem({ Text("Resolve playlist conflict") }, { close(resolve) }, leadingIcon = { Icon(AppIcon.UNDO.vector, null) })
                }
            }
            IconToggleButton(playlist.autoDownload, { controller.setPlaylistDownload(playlist.id, it) },
                modifier = Modifier.semantics {
                    contentDescription = "Automatically download playlist"
                    stateDescription = if (playlist.autoDownload) "Enabled" else "Disabled"
                }, colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary)) {
                Icon(AppIcon.DOWNLOAD.vector, null)
            }
            if (finishRearranging == null) Box {
                BadgedBox(badge = {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(playlist.sort.icon().vector, null, Modifier.size(14.dp).padding(1.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }) {
                    AppIconButton(AppIcon.SORT, "Playlist song order", Modifier.semantics {
                        stateDescription = "${playlist.sort.label()} · ${if (playlist.descending) "Descending" else "Ascending"}"
                    }) { sortMenu = true }
                }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    PlaylistSort.entries.forEach { sort ->
                        AppMenuItem({ Text(sort.label()) }, {
                            sortMenu = false; controller.setPlaylistSort(playlist.id, sort, sort == PlaylistSort.RECENT)
                        }, leadingIcon = { Icon(sort.icon().vector, null) },
                            trailingIcon = if (playlist.sort == sort) ({ Icon(AppIcon.CHECK.vector, null) }) else null)
                    }
                    HorizontalDivider()
                    AppMenuItem({ Text(if (playlist.descending) "Descending" else "Ascending") }, {
                        sortMenu = false; controller.setPlaylistSort(playlist.id, playlist.sort, !playlist.descending)
                    }, leadingIcon = { Icon((if (playlist.descending) AppIcon.DESCENDING else AppIcon.ASCENDING).vector, null) },
                        modifier = Modifier.semantics { contentDescription = "Playlist sort direction" })
                }
            }
            Spacer(Modifier.weight(1f))
            IconToggleButton(playlist.shuffle, { controller.setPlaylistShuffle(playlist.id, it) },
                modifier = Modifier.semantics { contentDescription = "Shuffle playlist"; selected = playlist.shuffle },
                colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer)) { Icon(AppIcon.SHUFFLE.vector, null) }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {
                val index = if (playlist.shuffle) Random.nextInt(orderedIds.size) else 0
                controller.playTrack(orderedIds[index], orderedIds, index, playlist.name, shuffleContext = playlist.shuffle, origin = origin)
            }, enabled = orderedIds.isNotEmpty(), modifier = Modifier.size(56.dp)) {
                Icon(AppIcon.PLAY.vector, "Play playlist", Modifier.size(32.dp))
            }
        }
        if (playlist.server != null) Text(if (playlist.pendingSync) "Changes waiting to sync" else "Server playlist",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EditPlaylistDialog(playlist: Playlist, state: AppState, controller: MusicController, dismiss: () -> Unit) {
    var name by remember(playlist.id) { mutableStateOf(playlist.title) }
    var folder by remember(playlist.id) { mutableStateOf(playlist.folder.orEmpty()) }
    var description by remember(playlist.id) { mutableStateOf(playlist.description) }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    var removeCover by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val picker = LocalArtworkPicker.current
    val scope = rememberCoroutineScope()
    var pickerError by remember { mutableStateOf<String?>(null) }
    val preview by produceState<ImageBitmap?>(null, cover) { value = cover?.let { withContext(Dispatchers.Default) { decodeArtwork(it, 256) } } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Edit playlist") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(120.dp).align(Alignment.CenterHorizontally), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                if (preview != null) Image(requireNotNull(preview), "Playlist cover preview", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else CollectionArtwork(if (removeCover && playlist.server != null) null else playlist.artworkPath(state, custom = !removeCover), playlist.name, AppIcon.QUEUE, Modifier.fillMaxSize())
            }
            OutlinedButton(onClick = {
                scope.launch {
                    picking = true
                    pickerError = null
                    try { picker.pick()?.let { cover = it; removeCover = false } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { pickerError = error.message ?: "Could not open this artwork" }
                    finally { picking = false }
                }
            }, enabled = !picking, modifier = Modifier.fillMaxWidth()) { Icon(AppIcon.ARTWORK_COLORS.vector, null); Spacer(Modifier.width(8.dp)); Text("Change cover") }
            if (state.artwork["playlist:${playlist.id}"] != null || cover != null || (playlist.server != null && !removeCover))
                TextButton(onClick = { cover = null; removeCover = true }) {
                Icon(AppIcon.REMOVE.vector, null); Spacer(Modifier.width(8.dp))
                Text(if (playlist.server != null) "Use generated cover" else "Use song artwork")
            }
            pickerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it }, label = { Text("Title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playlist title" })
            OutlinedTextField(folder, { folder = it.filter { c -> c != PLAYLIST_FOLDER_SEPARATOR } }, label = { Text("Folder") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playlist folder" })
            OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playlist description" })
        }
    }, confirmButton = { Button(onClick = { controller.editPlaylist(playlist.id, playlistName(folder, name), description, cover, removeCover); dismiss() },
        enabled = name.isNotBlank() && !picking) { Icon(AppIcon.CHECK.vector, null); Spacer(Modifier.width(8.dp)); Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

internal fun PlaylistSort.label() = when (this) {
    PlaylistSort.CUSTOM -> "Custom order"; PlaylistSort.TITLE -> "Title"; PlaylistSort.ARTIST -> "Artist"
    PlaylistSort.ALBUM -> "Album"; PlaylistSort.RECENT -> "Recently added"
}
internal fun PlaylistSort.icon() = when (this) {
    PlaylistSort.CUSTOM -> AppIcon.DRAG; PlaylistSort.TITLE -> AppIcon.TITLE; PlaylistSort.ARTIST -> AppIcon.ARTIST
    PlaylistSort.ALBUM -> AppIcon.ALBUM; PlaylistSort.RECENT -> AppIcon.RECENT
}
internal fun Playlist.orderedTracks(catalog: Map<String, Track>): List<IndexedValue<Track>> {
    val original = trackIds.mapIndexedNotNull { index, id -> catalog[id]?.let { IndexedValue(index, it) } }
    val comparator = when (sort) {
        PlaylistSort.CUSTOM -> compareBy<IndexedValue<Track>> { it.index }
        PlaylistSort.TITLE -> compareBy { it.value.metadata.title.lowercase() }
        PlaylistSort.ARTIST -> compareBy<IndexedValue<Track>> { it.value.metadata.artist.lowercase() }.thenBy { it.value.metadata.title.lowercase() }
        PlaylistSort.ALBUM -> compareBy<IndexedValue<Track>> { it.value.metadata.album.lowercase() }.thenBy { it.value.metadata.discNumber }.thenBy { it.value.metadata.trackNumber }
        PlaylistSort.RECENT -> compareBy { it.value.recentAt }
    }
    return original.sortedWith(if (descending) comparator.reversed() else comparator)
}
