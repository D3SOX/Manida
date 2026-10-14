package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.d3sox.musicplayer.playback.AudioWaveforms

@Composable
internal expect fun rememberAudioWaveforms(): AudioWaveforms

internal val LocalAudioWaveforms = staticCompositionLocalOf<AudioWaveforms?> { null }
