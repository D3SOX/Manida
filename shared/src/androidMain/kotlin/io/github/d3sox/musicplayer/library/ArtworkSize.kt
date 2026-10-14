package io.github.d3sox.musicplayer.library

import android.graphics.BitmapFactory

internal actual fun artworkSize(bytes: ByteArray): Pair<Int, Int> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return bounds.outWidth to bounds.outHeight
}
