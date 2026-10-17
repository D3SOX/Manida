package me.d3sox.nevumi

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.playback.NevumiAudioRenderersFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import kotlin.math.pow
import me.d3sox.nevumi.playback.AudioEffects
import me.d3sox.nevumi.playback.EqualizerBand

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AndroidEffectsOutputTest {
    @Test
    fun equalizerPreampMonoAndBypassChangeTheRenderedAudio() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "pitch-tone-${UUID.randomUUID()}.wav")
        val frames = 48_000 * 2
        val wav = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(48_000).putInt(192_000).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { frame ->
            for (frequency in listOf(440, 2_000)) wav.putShort((sin(2 * PI * frequency * frame / 48_000) * 8_000).toInt().toShort())
        }
        source.writeBytes(wav.array())
        try {
            suspend fun render(effects: AudioEffects): ShortArray {
                val captured = ByteArrayOutputStream()
                val ended = CompletableDeferred<Unit>()
                val capture = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
                    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                        if (sampleRateHz > 0) {
                            assertEquals(48_000, sampleRateHz)
                            assertEquals(2, channelCount)
                            assertEquals(C.ENCODING_PCM_16BIT, encoding)
                        }
                    }
                    override fun handleBuffer(buffer: ByteBuffer) {
                        val copy = ByteArray(buffer.remaining())
                        buffer.duplicate().get(copy)
                        captured.write(copy)
                    }
                })
                lateinit var player: ExoPlayer
                instrumentation.runOnMainSync {
                    player = ExoPlayer.Builder(context, NevumiAudioRenderersFactory(context, capture, effects = { effects })).build()
                    player.volume = 0f
                    player.playbackParameters = PlaybackParameters.DEFAULT
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            ended.completeExceptionally(error)
                        }
                    })
                    player.setMediaItem(MediaItem.fromUri(source.toURI().toString()))
                    player.prepare()
                    player.play()
                }
                try { withTimeout(12_000) { ended.await() } }
                finally { instrumentation.runOnMainSync { player.release() } }
                val samples = ByteBuffer.wrap(captured.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                return ShortArray(samples.remaining() / 2) { samples.short }
            }
            val flat = render(AudioEffects())
            val cutSettings = AudioEffects(enabled = true, bands = listOf(EqualizerBand(440.0, -12.0, 3.0)))
            val cut = render(cutSettings)
            val preamp = render(AudioEffects(enabled = true, preampDb = -6.0))
            val mono = render(AudioEffects(mono = true))
            val bypass = render(cutSettings.copy(enabled = false))
            val left = amplitude(flat, 0, 440)
            val right = amplitude(flat, 1, 2_000)
            assertEquals(10.0.pow(-12.0 / 20), amplitude(cut, 0, 440) / left, 0.02)
            assertEquals(1.0, amplitude(cut, 1, 2_000) / right, 0.03)
            assertEquals(10.0.pow(-6.0 / 20), amplitude(preamp, 0, 440) / left, 0.02)
            assertEquals(10.0.pow(-6.0 / 20), amplitude(preamp, 1, 2_000) / right, 0.02)
            assertEquals(1.0, amplitude(bypass, 0, 440) / left, 0.01)
            assertEquals(0.5, amplitude(mono, 0, 440) / left, 0.02)
            assertEquals(0.5, amplitude(mono, 1, 2_000) / right, 0.02)
            assertTrue((24_000 until 72_000).all { abs(mono[it * 2].toInt() - mono[it * 2 + 1].toInt()) <= 1 })
        } finally { source.delete() }
    }

    private fun amplitude(samples: ShortArray, channel: Int, frequency: Int): Double {
        assertTrue("Not enough rendered audio", samples.size >= 144_000)
        var real = 0.0
        var imaginary = 0.0
        for (frame in 24_000 until 72_000) {
            val sample = samples[frame * 2 + channel].toDouble()
            val phase = 2 * PI * frequency * frame / 48_000
            real += sample * cos(phase)
            imaginary += sample * sin(phase)
        }
        return 2 * hypot(real, imaginary) / 48_000
    }
}
