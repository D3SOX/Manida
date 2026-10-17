package me.d3sox.nevumi.playback

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

class LinuxDspPlaybackTest {
    @Test
    fun pitchChangesTheCapturedAudioFrequencyWhileSpeedIndependentlyControlsItsDuration() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-pitch-output")
        try {
            val source = directory.resolve("tone.wav")
            val pcm = ByteBuffer.allocate(48_000 * 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(48_000 * 3) { index -> pcm.putShort((sin(2 * PI * 440 * index / 48_000) * 15_000).toInt().toShort()) }
            AudioInputStream(ByteArrayInputStream(pcm.array()), AudioFormat(48_000f, 16, 1, true, false), 144_000).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, source.toFile())
            }
            suspend fun render(name: String, pitch: Double, speed: Double): FloatArray {
                val output = directory.resolve("$name.wav")
                val engine = MpvPlaybackEngine("pcm", pcmOutputFile = output.toString())
                try {
                    engine.setPitch(pitch)
                    engine.setSpeed(speed)
                    engine.load(PlayableTrack("tone", source.toString()))
                    try { withTimeout(5_000) { engine.status.first { it.ended } } }
                    catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                        throw AssertionError("Playback did not finish ($name): ${engine.status.value}", failure)
                    }
                } finally { engine.close() }
                return decode(output)
            }
            val original = render("original", 1.0, 1.0)
            val higher = render("higher", 2.0, 1.0)
            val faster = render("faster", 2.0, 1.5)
            assertTrue(abs(frequency(original) - 440) < 3)
            assertTrue(abs(frequency(higher) - 880) < 4)
            assertTrue(abs(frequency(faster) - 880) < 4)
            assertTrue(original.size / 48_000.0 in 2.9..3.1)
            assertTrue(higher.size / 48_000.0 in 2.85..3.15, "Pitch changed the duration")
            assertTrue(faster.size / 48_000.0 in 1.85..2.15, "Speed did not shorten playback independently")
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun decode(path: Path): FloatArray {
        val decoder = ProcessBuilder("ffmpeg", "-v", "error", "-i", path.toString(), "-ac", "1", "-ar", "48000",
            "-f", "f32le", "pipe:1").redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val bytes = decoder.inputStream.readAllBytes()
            assertTrue(decoder.waitFor(5, TimeUnit.SECONDS), "Audio decoder timed out")
            assertEquals(0, decoder.exitValue())
            val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { samples.float }
        } finally { decoder.destroyForcibly() }
    }

    private fun frequency(samples: FloatArray): Double {
        val start = samples.size / 3
        val count = minOf(48_000, samples.size - start - 1)
        assertTrue(count > 24_000, "Not enough captured audio")
        val crossings = (start + 1..start + count).count { samples[it - 1] <= 0 && samples[it] > 0 }
        return crossings * 48_000.0 / count
    }
}
