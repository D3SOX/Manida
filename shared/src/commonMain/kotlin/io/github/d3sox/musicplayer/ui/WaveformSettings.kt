package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.d3sox.musicplayer.app.AppState
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.playback.PreparedAudioWaveforms

@Composable
internal fun WaveformSettings(state: AppState, controller: MusicController) {
    val waveforms = LocalAudioWaveforms.current as? PreparedAudioWaveforms ?: return
    val progress by waveforms.preparation.collectAsState()
    SettingsGroup {
        SettingsToggle("Prepare automatically", AppIcon.EQUALIZER,
            "Prepare local and downloaded music in the background, starting with upcoming songs",
            state.preferences.precomputeWaveforms) { enabled ->
            controller.updatePreferences { it.copy(precomputeWaveforms = enabled) }
        }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${progress.prepared} of ${progress.total} prepared", style = MaterialTheme.typography.titleMedium)
            if (progress.running) {
                LinearProgressIndicator(progress = { if (progress.total == 0) 0f else progress.prepared.toFloat() / progress.total }, Modifier.fillMaxWidth())
                state.tracks.firstOrNull { track -> track.sources.any { it.id == progress.currentSourceId } }?.let {
                    Text(it.metadata.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (progress.failed > 0) Text("${progress.failed} could not be prepared", color = MaterialTheme.colorScheme.error)
            Text("Prepared waveforms are cached and reused when you play a song or reopen Manida. Server tracks need to be downloaded first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = { if (progress.running) waveforms.pause() else waveforms.prepareAll() }, Modifier.fillMaxWidth()) {
                Icon((if (progress.running) AppIcon.PAUSE else AppIcon.EQUALIZER).vector, null)
                Spacer(Modifier.width(8.dp)); Text(if (progress.running) "Pause preparation" else "Prepare all waveforms")
            }
        }
    }
}
