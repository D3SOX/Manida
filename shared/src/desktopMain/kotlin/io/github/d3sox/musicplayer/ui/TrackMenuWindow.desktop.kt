package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal actual fun TrackMenuHost(content: @Composable () -> Unit) = content()

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun TrackMenuWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false,
        scrimColor = Color.Transparent, animateTransition = false), content = content)
}
