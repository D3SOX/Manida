package me.d3sox.nevumi.ui

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException

internal fun readPlaylist(input: InputStream): String {
    val limit = 16 * 1024 * 1024
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "Choose a playlist smaller than 16 MiB" }
        output.write(buffer, 0, count)
    }
    val bytes = output.toByteArray()
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { throw IllegalArgumentException("Save the playlist as UTF-8 and try again") }
}
