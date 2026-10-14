package io.github.d3sox.musicplayer.artwork

/** Bound decoded memory rather than rejecting ordinary high resolution embedded covers. */
internal expect fun artworkPreview(bytes: ByteArray): ByteArray
