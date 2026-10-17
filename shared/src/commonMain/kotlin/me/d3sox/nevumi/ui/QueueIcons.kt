package me.d3sox.nevumi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Official Lucide list-end and list-start SVG paths, using their original 24px grid and 2px round strokes.
// Sources: https://lucide.dev/icons/list-end and https://lucide.dev/icons/list-start
// Copyright (c) 2026 Lucide Icons and Contributors. ISC license: composeResources/files/LICENSE-LUCIDE.
internal object QueueIcons {
    val append: ImageVector = lucideIcon("Lucide.list-end",
        "M16 5H3",
        "M16 12H3",
        "M9 19H3",
        "m16 16-3 3 3 3",
        "M21 5v12a2 2 0 0 1-2 2h-6")

    val playNext: ImageVector = lucideIcon("Lucide.list-start",
        "M3 5h6",
        "M3 12h13",
        "M3 19h13",
        "m16 8-3-3 3-3",
        "M21 19V7a2 2 0 0 0-2-2h-6")
}

private fun lucideIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { path ->
            addPath(PathParser().parsePathString(path).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()
