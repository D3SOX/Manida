package io.github.d3sox.musicplayer.artwork

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal actual fun artworkPreview(bytes: ByteArray): ByteArray {
    require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) { "Choose a valid artwork image" }
    return ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext()) { "Choose a valid artwork image" }
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0); val height = reader.getHeight(0)
            require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000) { "Choose a valid artwork image" }
            if (maxOf(width, height) <= 1024 && bytes.size <= 8 * 1024 * 1024) bytes
            else {
                val sample = ((maxOf(width, height) + 1023) / 1024).coerceAtLeast(1)
                val image = reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) })
                ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
        } finally { reader.dispose() }
    }
}
