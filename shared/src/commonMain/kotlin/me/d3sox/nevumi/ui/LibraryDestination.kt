package me.d3sox.nevumi.ui

import androidx.compose.runtime.compositionLocalOf
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.TrackMetadata

internal data class LibraryDestination(val tab: LibraryTab, val collectionId: String? = null, val revision: Int = 0, val trackId: String? = null)
internal val LocalLibraryNavigate = compositionLocalOf<(LibraryTab, String?) -> Unit> { { _, _ -> } }
internal val LocalLibraryOrigin = compositionLocalOf { me.d3sox.nevumi.queue.QueueOrigin() }
internal val LocalLibraryFocus = compositionLocalOf<LibraryDestination?> { null }
internal val LocalLibraryFocusHandled = compositionLocalOf<() -> Unit> { {} }
internal val LocalPlayingContextNavigate = compositionLocalOf<(me.d3sox.nevumi.queue.QueueOrigin, String) -> Unit> { { _, _ -> } }
internal fun albumCollectionId(metadata: TrackMetadata): String {
    val artist = metadata.albumArtist.ifBlank { metadata.artist }
    return "${metadata.album.length}:${metadata.album}$artist"
}
