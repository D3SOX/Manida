package io.github.d3sox.musicplayer.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

internal actual fun artworkPreview(bytes: ByteArray): ByteArray {
    require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) { "Choose a valid artwork image" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "Choose a valid artwork image" }
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    if (largest <= 1024 && bytes.size <= 8 * 1024 * 1024) return bytes
    var sample = 1
    while (largest / (sample * 2) >= 1024) sample *= 2
    val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
    val scale = minOf(1f, 1024f / maxOf(decoded.width, decoded.height))
    val preview = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1), true)
    return try { ByteArrayOutputStream().also { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
    finally { if (preview !== decoded) preview.recycle(); decoded.recycle() }
}
