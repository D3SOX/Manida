package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.compositionLocalOf
import io.github.d3sox.musicplayer.library.LibraryTab
import io.github.d3sox.musicplayer.library.TrackMetadata

internal data class LibraryDestination(val tab: LibraryTab, val collectionId: String? = null, val revision: Int = 0, val trackId: String? = null)
internal val LocalLibraryNavigate = compositionLocalOf<(LibraryTab, String?) -> Unit> { { _, _ -> } }
internal val LocalLibraryOrigin = compositionLocalOf { io.github.d3sox.musicplayer.queue.QueueOrigin() }
internal val LocalLibraryFocus = compositionLocalOf<LibraryDestination?> { null }
internal val LocalLibraryFocusHandled = compositionLocalOf<() -> Unit> { {} }
internal val LocalPlayingContextNavigate = compositionLocalOf<(io.github.d3sox.musicplayer.queue.QueueOrigin, String) -> Unit> { { _, _ -> } }
internal fun albumCollectionId(metadata: TrackMetadata): String {
    val artist = metadata.albumArtist.ifBlank { metadata.artist }
    return "${metadata.album.length}:${metadata.album}$artist"
}
