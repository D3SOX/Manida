package me.d3sox.nevumi.artwork

/** Bound decoded memory rather than rejecting ordinary high resolution embedded covers. */
internal expect fun artworkPreview(bytes: ByteArray): ByteArray
