package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable

fun interface FolderPicker {
    suspend fun pick(): String?
}

@Composable internal expect fun rememberFolderPicker(): FolderPicker
