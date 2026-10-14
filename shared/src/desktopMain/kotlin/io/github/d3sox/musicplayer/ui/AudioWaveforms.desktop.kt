package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.d3sox.musicplayer.playback.AudioWaveforms
import io.github.d3sox.musicplayer.playback.FfmpegAudioWaveforms
import java.io.File

@Composable
internal actual fun rememberAudioWaveforms(): AudioWaveforms = remember {
    val root = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: File(System.getProperty("user.home"), ".cache").path
    FfmpegAudioWaveforms(File(root, "manida/waveforms").path)
}
