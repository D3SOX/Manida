package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberArtworkPicker(): ArtworkPicker = remember { DesktopArtworkPicker() }

class DesktopArtworkPicker(address: String? = null) : ArtworkPicker {
    private val portal = PortalFileChooser(address)
    override suspend fun pick(): ByteArray? {
        val file = portal.choose("Choose artwork", filter = portalFilter("Images", "image/png", "image/jpeg")) ?: return null
        return withContext(Dispatchers.IO) { file.inputStream().use(::readArtwork) }
    }
}
