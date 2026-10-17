package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable

@Composable
internal expect fun rememberSystemAudioOutput(): () -> Boolean
