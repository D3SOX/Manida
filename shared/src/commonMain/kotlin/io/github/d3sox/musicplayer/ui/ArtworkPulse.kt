package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import io.github.d3sox.musicplayer.playback.PlaybackEngine
import kotlin.math.sqrt

@Composable
internal fun PulsingArtwork(engine: PlaybackEngine, enabled: Boolean, expansion: Float, content: @Composable BoxScope.() -> Unit) {
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val active = enabled && motionScale > 0f
    DisposableEffect(engine, active) {
        engine.setAudioLevelEnabled(active)
        onDispose { engine.setAudioLevelEnabled(false) }
    }
    val energy by engine.audioLevel.collectAsState()
    val target = if (active) 1f + .075f * sqrt(energy.coerceIn(0f, 1f)) else 1f
    val scale = animateFloatAsState(target, tween(durationMillis = 100, easing = LinearEasing),
        label = "Audio artwork pulse")
    // Only the compositing layer observes frame updates; artwork and its blur stay cached.
    Box(Modifier.fillMaxSize().graphicsLayer {
        val size = 1f + (scale.value - 1f) * expansion
        scaleX = size
        scaleY = size
    }.testTag("player-artwork"), content = content)
}
