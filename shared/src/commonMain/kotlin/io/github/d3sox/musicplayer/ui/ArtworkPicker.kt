package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

fun interface ArtworkPicker {
    suspend fun pick(): ByteArray?
}

internal val LocalArtworkPicker = staticCompositionLocalOf<ArtworkPicker> { error("Artwork picker is unavailable") }
@Composable internal expect fun rememberArtworkPicker(): ArtworkPicker
