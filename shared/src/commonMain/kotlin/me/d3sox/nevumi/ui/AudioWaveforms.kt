package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import me.d3sox.nevumi.playback.AudioWaveforms

@Composable
internal expect fun rememberAudioWaveforms(): AudioWaveforms

internal val LocalAudioWaveforms = staticCompositionLocalOf<AudioWaveforms?> { null }
