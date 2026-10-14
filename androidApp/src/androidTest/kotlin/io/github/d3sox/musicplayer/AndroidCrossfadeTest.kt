package io.github.d3sox.musicplayer

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.playback.AndroidExoPlaybackEngine
import io.github.d3sox.musicplayer.playback.CrossfadePlaybackEngine
import io.github.d3sox.musicplayer.playback.PlayableTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.hypot

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AndroidCrossfadeTest {
    @Test
    fun crossfadePlaysBothRealDecodersAndPausesAndResumesThemTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sources = listOf(440, 660).mapIndexed { index, frequency ->
            val source = File(context.cacheDir, "crossfade-$index-${UUID.randomUUID()}.wav")
            val frames = 48_000 * 3
            val wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(frames * 2)
            repeat(frames) { wav.putShort((sin(2 * PI * frequency * it / 48_000) * 6_000).toInt().toShort()) }
            source.writeBytes(wav.array())
            source
        }
        val captures = mutableListOf<ByteArrayOutputStream>()
        val engine = CrossfadePlaybackEngine {
            val captured = ByteArrayOutputStream().also { captures += it }
            AndroidExoPlaybackEngine(context, outputMonitor = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
                override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                    if (sampleRateHz > 0) {
                        assertEquals(48_000, sampleRateHz)
                        assertEquals(1, channelCount)
                        assertEquals(C.ENCODING_PCM_16BIT, encoding)
                    }
                }
                override fun handleBuffer(buffer: ByteBuffer) {
                    val copy = ByteArray(buffer.remaining())
                    buffer.duplicate().get(copy)
                    synchronized(captured) { captured.write(copy) }
                }
            }))
        }
        try {
            engine.setCrossfadeDuration(1_000)
            engine.loadQueue(sources.mapIndexed { index, file -> PlayableTrack("tone-$index", file.toURI().toString()) })
            withTimeout(8_000) { engine.status.first { it.playing && it.entryId == "tone-0" } }
            val started = System.nanoTime()
            val middle = withTimeout(5_000) { engine.status.first {
                it.entryId == "tone-1" && it.playing && it.positionMs >= 250 && it.fadingOut?.playing == true
            } }
            assertNotEquals(middle.playbackId, middle.fadingOut?.playbackId)
            assertEquals(1.0, middle.volume, 0.001)
            engine.pause()
            withTimeout(2_000) { engine.status.first { !it.playing && it.fadingOut?.playing == false } }
            delay(150)
            val paused = engine.status.value
            delay(250)
            assertEquals(paused.positionMs.toDouble(), engine.status.value.positionMs.toDouble(), 60.0)
            assertEquals(requireNotNull(paused.fadingOut).positionMs.toDouble(), requireNotNull(engine.status.value.fadingOut).positionMs.toDouble(), 60.0)
            engine.play()
            withTimeout(6_000) { engine.status.first { it.entryId == "tone-1" && it.ended } }
            assertEquals(5.4, (System.nanoTime() - started) / 1_000_000_000.0, 0.4)
            assertEquals(2, captures.size)
            captures.forEachIndexed { index, capture ->
                val samples = ByteBuffer.wrap(synchronized(capture) { capture.toByteArray() }).order(ByteOrder.LITTLE_ENDIAN)
                val pcm = ShortArray(samples.remaining() / 2) { samples.short }
                assertTrue("The real decoder did not render the track", pcm.size >= 140_000)
                assertEquals(6_000.0, amplitude(pcm, if (index == 0) 440 else 660), 20.0)
            }
        } finally {
            engine.close()
            sources.forEach { it.delete() }
        }
    }

    private fun amplitude(samples: ShortArray, frequency: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        for (frame in 24_000 until 72_000) {
            val phase = 2 * PI * frequency * frame / 48_000
            real += samples[frame] * cos(phase)
            imaginary += samples[frame] * sin(phase)
        }
        return 2 * hypot(real, imaginary) / 48_000
    }
}
