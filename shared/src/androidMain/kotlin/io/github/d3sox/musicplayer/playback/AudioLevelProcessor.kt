package io.github.d3sox.musicplayer.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Samples our decoded PCM; no microphone or AudioRecord permission is involved. */
@UnstableApi
internal class AudioLevelProcessor(private val position: () -> Long) : BaseAudioProcessor() {
    @Volatile var enabled = false
    private val levels = ArrayDeque<Pair<Long, Float>>()
    private var originMs = 0L
    private var frames = 0L
    private var windowFrames = 0
    private var energy = 0.0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush() = synchronized(levels) {
        levels.clear()
        originMs = position()
        frames = 0
        windowFrames = 0
        energy = 0.0
    }

    fun levelAt(positionMs: Long): Float = synchronized(levels) {
        while (levels.size > 1 && levels.elementAt(1).first <= positionMs) levels.removeFirst()
        levels.firstOrNull()?.takeIf { it.first <= positionMs && positionMs - it.first < 200 }?.second ?: 0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        run {
            val samples = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val channels = inputAudioFormat.channelCount
            val window = (inputAudioFormat.sampleRate / 50).coerceAtLeast(1)
            while (samples.remaining() >= channels) {
                repeat(channels) { val sample = samples.get() / 32_768.0; energy += sample * sample }
                frames++
                if (++windowFrames >= window) {
                    val level = sqrt(energy / (windowFrames * channels)).toFloat()
                    val at = originMs + (frames - windowFrames) * 1_000 / inputAudioFormat.sampleRate
                    synchronized(levels) { levels.addLast(at to level); while (levels.size > 2_048) levels.removeFirst() }
                    windowFrames = 0
                    energy = 0.0
                }
            }
        }
        replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }
}
