package me.d3sox.nevumi.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun readEqualizer(input: InputStream): String {
    val bytes = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(chunk)
        if (count < 0) break
        require(bytes.size() + count <= 65_536) { "Choose an equalizer file smaller than 64 KiB" }
        if (count > 0) bytes.write(chunk, 0, count)
    }
    return bytes.toString(Charsets.UTF_8.name())
}
