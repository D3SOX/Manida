package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.d3sox.musicplayer.app.MusicController
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

@Composable
internal actual fun AppVolume(controller: MusicController, modifier: Modifier) {
    val volumes = remember(controller) { controller.engine.status.map { it.volume }.distinctUntilChanged() }
    val volume by volumes.collectAsState(controller.engine.status.value.volume)
    Row(modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon((if (volume == 0.0) AppIcon.MUTE else AppIcon.VOLUME).vector, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(volume.toFloat(), { controller.setVolume(it.toDouble()) }, valueRange = 0f..1f,
            colors = appSliderColors(), modifier = Modifier.weight(1f).semantics { contentDescription = "App volume" })
        Text("${(volume * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
