package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.d3sox.nevumi.playback.AudioWaveforms
import me.d3sox.nevumi.playback.FfmpegAudioWaveforms
import java.io.File

@Composable
internal actual fun rememberAudioWaveforms(): AudioWaveforms = remember {
    val root = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: File(System.getProperty("user.home"), ".cache").path
    FfmpegAudioWaveforms(File(root, "nevumi/waveforms").path)
}
