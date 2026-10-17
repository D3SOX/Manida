package me.d3sox.nevumi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PlayerArtworkGlow(path: String?, opacity: Float) {
    val artwork = rememberArtwork(path, maxDimension = 64) ?: return
    val glow by produceState<ImageBitmap?>(null, artwork) {
        value = withContext(Dispatchers.Default) { artwork.renderGlow() }
    }
    glow?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { scaleX = 2.8f; scaleY = 2.8f },
        alpha = opacity.coerceIn(0f, 1f), contentScale = ContentScale.FillBounds) }
}

private fun ImageBitmap.renderGlow(): ImageBitmap {
    val glow = ImageBitmap(256, 256)
    val canvas = Canvas(glow)
    canvas.drawRect(Rect(0f, 0f, 256f, 256f), Paint().apply { color = Color.Transparent; blendMode = BlendMode.Src })
    val paint = Paint()
    glowColors().forEachIndexed { index, color ->
        val center = Offset(if (index % 2 == 0) 96f else 160f, if (index < 2) 96f else 160f)
        Brush.radialGradient(listOf(color.copy(alpha = color.alpha * .72f), Color.Transparent), center, 90f)
            .applyTo(Size(256f, 256f), paint, 1f)
        canvas.drawCircle(center, 90f, paint)
    }
    return glow
}

private fun ImageBitmap.glowColors(): List<Color> {
    val pixels = toPixelMap()
    val sums = Array(4) { FloatArray(4) }
    for (y in 0 until height step 4) for (x in 0 until width step 4) {
        val color = pixels[x, y]
        val sum = sums[(if (y < height / 2) 0 else 2) + if (x < width / 2) 0 else 1]
        sum[0] += color.red * color.alpha; sum[1] += color.green * color.alpha
        sum[2] += color.blue * color.alpha; sum[3] += color.alpha
    }
    return sums.map { if (it[3] == 0f) Color.Transparent else Color(it[0] / it[3], it[1] / it[3], it[2] / it[3]) }
}
