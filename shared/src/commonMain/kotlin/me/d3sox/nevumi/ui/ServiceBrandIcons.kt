package me.d3sox.nevumi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Original brand geometry, with its original proportions and colors.
// ListenBrainz: https://github.com/metabrainz/listenbrainz-server/blob/master/frontend/img/listenbrainz_logo_icon.svg
// Last.fm: https://github.com/simple-icons/simple-icons/blob/develop/icons/lastdotfm.svg
// Last.fm vector distributed under CC0: composeResources/files/LICENSE-SIMPLE-ICONS.
internal object ServiceBrandIcons {
    val listenBrainz = ImageVector.Builder("ListenBrainz", 24.dp, (24f * 30 / 27).dp, 27f, 30f).apply {
        addPath(PathParser().parsePathString("M13 1L1 8V22L13 29Z").toNodes(), fill = SolidColor(Color(0xFF353070)))
        addPath(PathParser().parsePathString("M14 1L26 8V22L14 29Z").toNodes(), fill = SolidColor(Color(0xFFEB743B)))
    }.build()
    val lastFm = ImageVector.Builder("Last.fm", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString("M10.584 17.21l-.88-2.392s-1.43 1.594-3.573 1.594c-1.897 0-3.244-1.649-3.244-4.288 0-3.382 1.704-4.591 3.381-4.591 2.42 0 3.189 1.567 3.849 3.574l.88 2.749c.88 2.666 2.529 4.81 7.285 4.81 3.409 0 5.718-1.044 5.718-3.793 0-2.227-1.265-3.381-3.63-3.931l-1.758-.385c-1.21-.275-1.567-.77-1.567-1.595 0-.934.742-1.484 1.952-1.484 1.32 0 2.034.495 2.144 1.677l2.749-.33c-.22-2.474-1.924-3.492-4.729-3.492-2.474 0-4.893.935-4.893 3.932 0 1.87.907 3.051 3.189 3.601l1.87.44c1.402.33 1.869.907 1.869 1.704 0 1.017-.99 1.43-2.86 1.43-2.776 0-3.93-1.457-4.59-3.464l-.907-2.75c-1.155-3.573-2.997-4.893-6.653-4.893C2.144 5.333 0 7.89 0 12.233c0 4.18 2.144 6.434 5.993 6.434 3.106 0 4.591-1.457 4.591-1.457z").toNodes(), fill = SolidColor(Color(0xFFD51007)))
    }.build()
}
