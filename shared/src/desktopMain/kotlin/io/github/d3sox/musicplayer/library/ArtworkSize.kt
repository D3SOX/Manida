package io.github.d3sox.musicplayer.library

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

internal actual fun artworkSize(bytes: ByteArray): Pair<Int, Int> =
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext()) { "Choose a valid image" }
        val reader = readers.next()
        try { reader.input = input; reader.getWidth(0) to reader.getHeight(0) }
        finally { reader.dispose() }
    }
