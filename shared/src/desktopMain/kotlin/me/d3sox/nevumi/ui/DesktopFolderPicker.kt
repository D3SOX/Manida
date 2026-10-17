package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberFolderPicker(): FolderPicker = remember { DesktopFolderPicker() }

class DesktopFolderPicker(address: String? = null) : FolderPicker {
    private val portal = PortalFileChooser(address)
    override suspend fun pick(): String? = portal.choose("Choose music folder", directory = true)?.absolutePath
}
