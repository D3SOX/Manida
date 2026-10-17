package me.d3sox.nevumi.playback

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.*
import kotlin.test.*

class LinuxEffectsOutputTest {
    @Test
    fun equalizerPreampMonoAndBypassChangeTheActualRenderedAudio() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-equalizer-output")
        try {
            val source = directory.resolve("stereo.wav")
            val frames = 48_000 * 2
            val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { index ->
                for (frequency in listOf(440, 2_000)) pcm.putShort((sin(2 * PI * frequency * index / 48_000) * 8_000).toInt().toShort())
            }
            AudioInputStream(ByteArrayInputStream(pcm.array()), AudioFormat(48_000f, 16, 2, true, false), frames.toLong()).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, source.toFile())
            }
            suspend fun render(name: String, effects: AudioEffects): FloatArray {
                val output = directory.resolve("$name.wav")
                val engine = MpvPlaybackEngine("pcm", pcmOutputFile = output.toString())
                try {
                    engine.setAudioEffects(effects)
                    engine.load(PlayableTrack("stereo", source.toString()))
                    withTimeout(5_000) { engine.status.first { it.ended } }
                } finally { engine.close() }
                val decoder = ProcessBuilder("ffmpeg", "-v", "error", "-i", output.toString(), "-ac", "2", "-ar", "48000",
                    "-f", "f32le", "pipe:1").redirectError(ProcessBuilder.Redirect.INHERIT).start()
                try {
                    val bytes = decoder.inputStream.readAllBytes()
                    assertTrue(decoder.waitFor(5, TimeUnit.SECONDS))
                    assertEquals(0, decoder.exitValue())
                    val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    return FloatArray(bytes.size / 4) { samples.float }
                } finally { decoder.destroyForcibly() }
            }
            val flat = render("flat", AudioEffects())
            val cutSettings = AudioEffects(enabled = true, bands = listOf(EqualizerBand(440.0, -12.0, 3.0)))
            val cut = render("cut", cutSettings)
            val preamp = render("preamp", AudioEffects(enabled = true, preampDb = -6.0))
            val mono = render("mono", AudioEffects(mono = true))
            val bypass = render("bypass", cutSettings.copy(enabled = false))
            val left = amplitude(flat, 0, 440)
            val right = amplitude(flat, 1, 2_000)
            assertEquals(10.0.pow(-12.0 / 20), amplitude(cut, 0, 440) / left, 0.02)
            assertEquals(1.0, amplitude(cut, 1, 2_000) / right, 0.03)
            assertEquals(10.0.pow(-6.0 / 20), amplitude(preamp, 0, 440) / left, 0.02)
            assertEquals(10.0.pow(-6.0 / 20), amplitude(preamp, 1, 2_000) / right, 0.02)
            assertEquals(1.0, amplitude(bypass, 0, 440) / left, 0.01)
            assertEquals(0.5, amplitude(mono, 0, 440) / left, 0.02)
            assertEquals(0.5, amplitude(mono, 1, 2_000) / right, 0.02)
            assertTrue((24_000 until 72_000).all { abs(mono[it * 2] - mono[it * 2 + 1]) < 0.0001 })
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun amplitude(samples: FloatArray, channel: Int, frequency: Int): Double {
        assertTrue(samples.size >= 144_000, "Not enough captured audio")
        var real = 0.0
        var imaginary = 0.0
        for (frame in 24_000 until 72_000) {
            val sample = samples[frame * 2 + channel]
            val phase = 2 * PI * frequency * frame / 48_000
            real += sample * cos(phase)
            imaginary += sample * sin(phase)
        }
        return 2 * hypot(real, imaginary) / 48_000
    }
}
