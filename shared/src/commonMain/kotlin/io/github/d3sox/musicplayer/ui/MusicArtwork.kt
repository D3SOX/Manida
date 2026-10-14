package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

internal expect fun decodeArtwork(bytes: ByteArray, maxDimension: Int): ImageBitmap?

@Composable
internal fun rememberArtwork(path: String?, maxDimension: Int = 256): ImageBitmap? {
    val image by produceState(ArtworkMemoryCache.peek(path, maxDimension), path, maxDimension) {
        value = ArtworkMemoryCache.peek(path, maxDimension)
        if (path != null) value = ArtworkMemoryCache.load(path, maxDimension)
    }
    return image
}

@Composable
internal fun MusicArtwork(path: String?, title: String, description: String? = null, maxDimension: Int = 256) {
    Box(Modifier.fillMaxSize()) {
        val image = rememberArtwork(path, maxDimension)
        if (image != null) Image(image, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(AppIcon.MUSIC.vector, description, modifier = Modifier.fillMaxSize(.45f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
