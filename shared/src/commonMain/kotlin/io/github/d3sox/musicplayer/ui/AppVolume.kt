package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.d3sox.musicplayer.app.MusicController

@Composable
internal expect fun AppVolume(controller: MusicController, modifier: Modifier = Modifier)
