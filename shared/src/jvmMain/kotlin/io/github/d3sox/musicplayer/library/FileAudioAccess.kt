package io.github.d3sox.musicplayer.library

import java.io.File
import java.net.URI
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest

actual object FileAudioAccess {
    actual fun available(location: String): Boolean = try {
        val file = if (location.startsWith("file:")) File(URI(location)) else File(location)
        file.isFile && file.canRead()
    } catch (_: IllegalArgumentException) { false }

    actual fun contentId(location: String): AudioContentId? = try {
        val file = if (location.startsWith("file:")) File(URI(location)) else File(location)
        file.inputStream().use { it.audioContentId() }
    } catch (_: IOException) { null }
    catch (_: IllegalArgumentException) { null }
    catch (_: SecurityException) { null }
}

internal fun InputStream.audioContentId(): AudioContentId {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
        size += count
    }
    return AudioContentId(size, digest.digest().joinToString("") { "%02x".format(it) })
}
