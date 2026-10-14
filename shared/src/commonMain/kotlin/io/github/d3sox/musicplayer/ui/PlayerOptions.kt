package io.github.d3sox.musicplayer.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.playback.RepeatMode
import io.github.d3sox.musicplayer.queue.QueueSnapshot

@Composable
internal fun PlayerRepeatControl(queue: QueueSnapshot, controller: MusicController, onRepeatCount: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        val active = queue.repeatMode != RepeatMode.OFF
        FilledIconButton(onClick = { menu = true }, modifier = modifier.semantics {
            selected = active; stateDescription = queue.repeatMode.name
        }, colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
            Icon((if (queue.repeatMode == RepeatMode.ONE) AppIcon.REPEAT_ONE else AppIcon.REPEAT).vector, "Repeat")
        }
        DropdownMenu(menu, { menu = false }) {
            listOf(RepeatMode.OFF to "Off", RepeatMode.ONE to "Song", RepeatMode.ALL to "Queue").forEach { (mode, name) ->
                AppMenuItem(text = { Text(name) }, leadingIcon = { Icon(when (mode) {
                    RepeatMode.OFF -> AppIcon.CLOSE; RepeatMode.ONE -> AppIcon.REPEAT_ONE; RepeatMode.ALL -> AppIcon.REPEAT
                }.vector, null) }, trailingIcon = { if (mode == queue.repeatMode) Icon(AppIcon.CHECK.vector, null) },
                    onClick = { controller.setQueueRepeat(mode); menu = false })
            }
            HorizontalDivider()
            AppMenuItem(text = { Text("Repeat count") }, leadingIcon = { Icon(AppIcon.REPEAT_COUNT.vector, null) },
                onClick = { menu = false; onRepeatCount() })
            queue.repeatPlays?.let { remaining ->
                AppMenuItem(text = { Text(if (remaining == 1) "1 play remaining" else "$remaining plays remaining") }, enabled = false, onClick = {})
            }
        }
    }
}
