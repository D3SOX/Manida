package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.d3sox.musicplayer.playback.AudioWaveforms
import io.github.d3sox.musicplayer.playback.AndroidAudioWaveforms

@Composable
internal actual fun rememberAudioWaveforms(): AudioWaveforms {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidAudioWaveforms(context) }
}
