package io.github.d3sox.musicplayer.library

import android.content.Context
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException

class AndroidAudioAccess(private val context: Context) {
    fun contentId(location: String): AudioContentId? {
        if (!location.startsWith("content:")) return FileAudioAccess.contentId(location)
        return try { context.contentResolver.openInputStream(Uri.parse(location))?.use { it.audioContentId() } }
        catch (_: IOException) { null }
        catch (_: SecurityException) { null }
    }

    fun available(location: String): Boolean {
        if (!location.startsWith("content:")) return FileAudioAccess.available(location)
        return try { context.contentResolver.openAssetFileDescriptor(Uri.parse(location), "r")?.use { true } ?: false }
        catch (_: FileNotFoundException) { false }
        catch (_: SecurityException) { false }
    }
}
