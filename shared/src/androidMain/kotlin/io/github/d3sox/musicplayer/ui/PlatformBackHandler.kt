package io.github.d3sox.musicplayer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException

@Composable
internal actual fun PlatformBackHandler(onBack: () -> Unit) { BackHandler(enabled = LocalNavigationActive.current, onBack = onBack) }

@Composable
internal actual fun PlatformPredictiveBackHandler(enabled: Boolean, onStarted: () -> Unit,
    onProgress: (Float) -> Unit, onCancelled: () -> Unit, onBack: () -> Unit) {
    PredictiveBackHandler(enabled = enabled) { events ->
        onStarted()
        try { events.collect { onProgress(it.progress) } }
        catch (cancelled: CancellationException) { onCancelled(); throw cancelled }
        onBack()
    }
}
