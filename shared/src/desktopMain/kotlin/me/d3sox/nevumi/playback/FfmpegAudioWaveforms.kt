package me.d3sox.nevumi.playback

import me.d3sox.nevumi.library.TrackSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal actual fun waveformPreparationParallelism(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

class FfmpegAudioWaveforms(directory: String) : AudioWaveforms {
    private val cached = CachedAudioWaveforms(directory, ::decode)
    override suspend fun load(source: TrackSource, durationMs: Long) = cached.load(source, durationMs)
    override suspend fun cached(source: TrackSource) = cached.cached(source)
    private suspend fun decode(location: String, durationMs: Long): List<Float> {
        val process = ProcessBuilder("ffmpeg", "-v", "error", "-i", location, "-ac", "2", "-ar", "8000", "-f", "f32le", "pipe:1")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val energy = DoubleArray(128)
            val counts = LongArray(128)
            val total = (durationMs * 16).coerceAtLeast(1)
            var sample = 0L
            val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            process.inputStream.use { pcm ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = pcm.read(buffer.array(), buffer.position(), buffer.remaining())
                    if (read < 0) break
                    buffer.limit(buffer.position() + read).position(0)
                    val floats = buffer.asFloatBuffer()
                    while (floats.hasRemaining()) {
                        val band = (sample * 128 / total).coerceIn(0, 127).toInt()
                        val end = if (band == 127) Long.MAX_VALUE else ((band + 1) * total + 127) / 128
                        val count = minOf(floats.remaining().toLong(), end - sample).toInt()
                        repeat(count) {
                            val value = floats.get()
                            if (value.isFinite()) { energy[band] += value.toDouble() * value; counts[band]++ }
                        }
                        sample += count
                    }
                    // Pipe reads may end between the four bytes of a PCM float.
                    buffer.position(floats.position() * 4).compact()
                }
            }
            check(process.waitFor() == 0 && sample > 0)
            return energy.indices.map { sqrt(energy[it] / counts[it].coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) }
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
