package io.github.d3sox.musicplayer.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal expect fun waveformPreparationParallelism(): Int

internal expect class WaveformPreparationThread(parallelism: Int) : AutoCloseable {
    val dispatcher: CoroutineDispatcher
}

internal class WaveformPreparationContext(val dispatcher: CoroutineDispatcher) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WaveformPreparationContext>
}
