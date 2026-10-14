package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable

fun interface FolderPicker {
    suspend fun pick(): String?
}

@Composable internal expect fun rememberFolderPicker(): FolderPicker
