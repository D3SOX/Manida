package me.d3sox.nevumi.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal actual class WaveformPreparationThread actual constructor(parallelism: Int) : AutoCloseable {
    private val sequence = AtomicInteger()
    private val worker = Executors.newFixedThreadPool(parallelism) { work ->
        Thread(work, "Nevumi waveform preparation ${sequence.incrementAndGet()}").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    actual val dispatcher: CoroutineDispatcher = worker
    override fun close() = worker.close()
}
