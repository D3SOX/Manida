package io.github.d3sox.musicplayer.visualizer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/** A bounded buffer of our own playback audio. Rendering runs outside its copy lock. */
object VisualizerPcm {
    private val consumers = AtomicInteger()
    val enabled: Boolean get() = consumers.get() > 0
    private val lock = Any()
    private val samples = FloatArray(4096)
    private var written = 0
    private var size = 0
    private var rateAccumulator = 0L

    fun subscribe(): AutoCloseable {
        consumers.incrementAndGet()
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                val left = consumers.decrementAndGet()
                if (left == 0) synchronized(lock) { size = 0; rateAccumulator = 0 }
            }
        }
    }

    fun write16(input: ByteBuffer, channels: Int, sampleRate: Int) {
        if (!enabled || channels !in 1..32 || sampleRate <= 0) return
        val pcm = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        synchronized(lock) {
            while (pcm.remaining() >= channels * 2) {
                val left = pcm.short / 32768f
                val right = if (channels == 1) left else pcm.short / 32768f
                if (channels > 2) pcm.position(pcm.position() + (channels - 2) * 2)
                rateAccumulator += 44100
                while (rateAccumulator >= sampleRate) { append(left, right); rateAccumulator -= sampleRate }
            }
        }
    }

    fun writeStereo(input: FloatArray) {
        if (!enabled) return
        synchronized(lock) {
            for (index in 0 until input.size - 1 step 2) append(input[index], input[index + 1])
        }
    }

    private fun append(left: Float, right: Float) {
        samples[written] = if (left.isFinite()) left.coerceIn(-1f, 1f) else 0f
        samples[written + 1] = if (right.isFinite()) right.coerceIn(-1f, 1f) else 0f
        written = (written + 2) % samples.size
        size = (size + 2).coerceAtMost(samples.size)
    }

    fun consume(): FloatArray = synchronized(lock) {
        val count = minOf(size, 2048)
        val start = (written - count + samples.size) % samples.size
        FloatArray(count) { samples[(start + it) % samples.size] }.also { size = 0 }
    }
}
