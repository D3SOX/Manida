package io.github.d3sox.musicplayer.downloads

import java.io.File

internal actual fun downloadUsableSpace(directory: String): Long =
    generateSequence(File(directory).absoluteFile) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0
