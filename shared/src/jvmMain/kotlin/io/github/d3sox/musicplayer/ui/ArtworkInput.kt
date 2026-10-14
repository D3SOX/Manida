package io.github.d3sox.musicplayer.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun readArtwork(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= 64 * 1024 * 1024) { "Choose a valid artwork image" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
