package me.d3sox.nevumi.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalNavigationActive = compositionLocalOf { true }
internal val LocalPageBackHandled = compositionLocalOf { false }

/** The same transition is driven by navigation clicks and Android's back-gesture progress. */
@Composable
internal fun <T> AnimatedNavigation(target: T, parent: T, canGoBack: Boolean,
    onBack: () -> Unit, modifier: Modifier = Modifier, depth: (T) -> Int,
    content: @Composable (T) -> Unit) {
    val navigationActive = LocalNavigationActive.current
    val state = remember { SeekableTransitionState(target) }
    val transition = rememberTransition(state, "Page navigation")
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    var gesture by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(target, gesture) {
        if (!gesture) {
            // Forward navigation opens a fresh page; only going back restores saved state.
            if (depth(target) > depth(state.currentState)) holder.removeState(target.toString())
            if (state.currentState == target && state.targetState != target) {
                // Reverse the existing back preview; retargeting would start a new entrance.
                coroutineScope {
                    animate(state.fraction, 0f, animationSpec = tween((state.fraction * 250).roundToInt())) { fraction, _ ->
                        launch { state.seekTo(fraction) }
                    }
                }
                state.snapTo(target)
            } else state.animateTo(target)
        }
    }
    PlatformPredictiveBackHandler(enabled = navigationActive && canGoBack,
        onStarted = { gesture = true },
        onProgress = { progress ->
            seeking?.cancel()
            seeking = scope.launch {
                if (state.currentState != target) state.snapTo(target)
                state.seekTo(progress, parent)
            }
        },
        onCancelled = { seeking?.cancel(); gesture = false },
        onBack = { seeking?.cancel(); gesture = false; onBack() })
    transition.AnimatedContent(modifier.fillMaxSize().onKeyEvent {
        if (navigationActive && canGoBack && it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onBack(); true } else false
    }, transitionSpec = {
        val forward = depth(targetState) > depth(initialState)
        val enter = slideInHorizontally(tween(250)) { if (forward) it else -it / 4 } + fadeIn(tween(180))
        val exit = slideOutHorizontally(tween(250)) { if (forward) -it / 4 else it } + fadeOut(tween(180))
        (enter togetherWith exit).apply { targetContentZIndex = if (forward) 1f else -1f }
    }) { page ->
        val active = navigationActive && page == target
        holder.SaveableStateProvider(page.toString()) {
            CompositionLocalProvider(LocalNavigationActive provides active, LocalPageBackHandled provides true) {
                Box(Modifier.fillMaxSize().then(if (active) Modifier else Modifier.clearAndSetSemantics {})) { content(page) }
            }
        }
    }
}
