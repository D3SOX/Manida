package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.d3sox.nevumi.app.MusicController

@Composable
internal expect fun AppVolume(controller: MusicController, modifier: Modifier = Modifier)
