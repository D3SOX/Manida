package io.github.d3sox.musicplayer.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformBackHandler(onBack: () -> Unit) = Unit

@Composable
internal actual fun PlatformPredictiveBackHandler(enabled: Boolean, onStarted: () -> Unit,
    onProgress: (Float) -> Unit, onCancelled: () -> Unit, onBack: () -> Unit) = Unit
