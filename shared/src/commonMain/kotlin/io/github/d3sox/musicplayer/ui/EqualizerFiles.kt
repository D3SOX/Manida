package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

data class EqualizerDocument(val name: String, val text: String)
fun interface EqualizerFiles { suspend fun open(): EqualizerDocument? }
internal val LocalEqualizerFiles = staticCompositionLocalOf<EqualizerFiles> { error("Equalizer files are unavailable") }
@Composable internal expect fun rememberEqualizerFiles(): EqualizerFiles
