package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable

/** The menu draws its own scrim so it follows the cover and content animation. */
@Composable
internal expect fun TrackMenuWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit)

@Composable
internal expect fun TrackMenuHost(content: @Composable () -> Unit)
