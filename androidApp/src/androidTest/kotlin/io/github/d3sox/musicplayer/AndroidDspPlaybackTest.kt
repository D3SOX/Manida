package io.github.d3sox.musicplayer

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.playback.ManidaAudioRenderersFactory
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

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AndroidDspPlaybackTest {
    @Test
    fun pitchChangesRenderedPcmWhileSpeedIndependentlyControlsItsDuration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "pitch-tone-${UUID.randomUUID()}.wav")
        val frames = 48_000 * 3
        val wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames * 2)
        repeat(frames) { wav.putShort((sin(2 * PI * 440 * it / 48_000) * 15_000).toInt().toShort()) }
        source.writeBytes(wav.array())
        try {
            suspend fun render(pitch: Float, speed: Float): ShortArray {
                val captured = ByteArrayOutputStream()
                val ended = CompletableDeferred<Unit>()
                val capture = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
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
                        captured.write(copy)
                    }
                })
                lateinit var player: ExoPlayer
                instrumentation.runOnMainSync {
                    player = ExoPlayer.Builder(context, ManidaAudioRenderersFactory(context, capture)).build()
                    player.volume = 0f
                    player.playbackParameters = PlaybackParameters(speed, pitch)
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
            val original = render(1f, 1f)
            val higher = render(2f, 1f)
            val faster = render(2f, 1.5f)
            assertTrue(abs(frequency(original) - 440) < 3)
            assertTrue(abs(frequency(higher) - 880) < 4)
            assertTrue(abs(frequency(faster) - 880) < 4)
            assertTrue(original.size / 48_000.0 in 2.9..3.1)
            assertTrue(higher.size / 48_000.0 in 2.85..3.15)
            assertTrue(faster.size / 48_000.0 in 1.85..2.15)
        } finally { source.delete() }
    }

    private fun frequency(samples: ShortArray): Double {
        val start = samples.size / 3
        val count = minOf(48_000, samples.size - start - 1)
        assertTrue("Not enough rendered audio", count > 24_000)
        return (start + 1..start + count).count { samples[it - 1] <= 0 && samples[it] > 0 } * 48_000.0 / count
    }
}
