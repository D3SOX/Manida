package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import me.d3sox.nevumi.playback.AudioWaveforms
import me.d3sox.nevumi.playback.AndroidAudioWaveforms

@Composable
internal actual fun rememberAudioWaveforms(): AudioWaveforms {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidAudioWaveforms(context) }
}
