package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

internal actual fun decodeArtwork(bytes: ByteArray, maxDimension: Int): ImageBitmap? {
    val sampled = javax.imageio.ImageIO.createImageInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
        val readers = javax.imageio.ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = input
            val largest = maxOf(reader.getWidth(0), reader.getHeight(0))
            if (largest <= maxDimension) bytes else {
                val sample = ((largest + maxDimension - 1) / maxDimension).coerceAtLeast(1)
                val picture = reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) })
                java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(picture, "png", it) }.toByteArray()
            }
        } finally { reader.dispose() }
    }
    val image = Image.makeFromEncoded(sampled)
    return if (image.width.toLong() * image.height > 16_000_000) null else image.toComposeImageBitmap()
}
