package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

data class PlaylistDocument(val name: String, val text: String, val baseLocation: String = "")

interface PlaylistFiles {
    suspend fun open(): PlaylistDocument?
    suspend fun save(name: String, text: String): Boolean
}

internal val LocalPlaylistFiles = staticCompositionLocalOf<PlaylistFiles> { error("Playlist files are unavailable") }
@Composable internal expect fun rememberPlaylistFiles(): PlaylistFiles
