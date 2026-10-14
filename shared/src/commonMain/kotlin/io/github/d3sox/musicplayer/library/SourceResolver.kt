package io.github.d3sox.musicplayer.library

import kotlinx.serialization.Serializable

@Serializable
enum class SourceKind { LOCAL, DOWNLOAD, SERVER }

@Serializable
data class TrackSource(
    val id: String,
    val kind: SourceKind,
    val location: String,
    val available: Boolean = true,
    val filePath: String? = null,
    val fileLibraryId: Long? = null,
    val modifiedAt: Long = 0,
    val indexedSize: Long = 0,
    val hasArtwork: Boolean = false,
)

object SourceResolver {
    fun resolve(
        sources: List<TrackSource>,
        priority: List<SourceKind> = listOf(SourceKind.LOCAL, SourceKind.DOWNLOAD, SourceKind.SERVER),
        online: Boolean = true,
    ): TrackSource? = priority.firstNotNullOfOrNull { kind ->
        sources.firstOrNull { it.kind == kind && it.available && (online || kind != SourceKind.SERVER) }
    }
}
