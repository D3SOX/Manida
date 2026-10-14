package io.github.d3sox.musicplayer.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sound.sampled.*
import kotlin.math.*
import kotlin.test.*

class LinuxCrossfadeOutputTest {
    @Test
    fun crossfadeMixesBothTracksAndSurvivesPauseAndGaplessChanges() = runBlocking {
        val directory = Files.createTempDirectory("manida-crossfade-output")
        val sink = "manida_test_${UUID.randomUUID().toString().replace("-", "") }"
        var module: String? = null
        val previousSink = command("pactl", "get-default-sink").trim()
        try {
            module = command("pactl", "load-module", "module-null-sink", "sink_name=$sink", "rate=48000", "channels=2",
                "sink_properties=\"device.description=Manida_test priority.session=0\"").trim()
            if (command("pactl", "get-default-sink").trim() == sink) command("pactl", "set-default-sink", previousSink)
            val sources = listOf(440, 660).mapIndexed { index, frequency ->
                val source = directory.resolve("tone-$index.wav")
                val pcm = ByteBuffer.allocate(48_000 * 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(48_000 * 3) { pcm.putShort((sin(2 * PI * frequency * it / 48_000) * 6_000).toInt().toShort()) }
                AudioInputStream(ByteArrayInputStream(pcm.array()), AudioFormat(48_000f, 16, 1, true, false), 144_000).use {
                    AudioSystem.write(it, AudioFileFormat.Type.WAVE, source.toFile())
                }
                PlayableTrack("tone-$index", source.toString(), entryId = if (index == 0) "first" else "second")
            }
            suspend fun render(name: String, crossfadeMs: Long, pauseDuringFade: Boolean): FloatArray {
                val output = directory.resolve("$name.pcm")
                val capture = ProcessBuilder("parec", "--device=$sink.monitor", "--format=float32le", "--rate=48000", "--channels=2",
                    "--latency-msec=20").redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
                val engine = CrossfadePlaybackEngine { MpvPlaybackEngine("pulse", audioDevice = "pulse/$sink") }
                try {
                    withTimeout(3_000) { while (Files.size(output) < 48_000) delay(25) }
                    engine.setCrossfadeDuration(crossfadeMs)
                    engine.loadQueue(sources)
                    if (pauseDuringFade) {
                        val middle = withTimeout(5_000) { engine.status.first {
                            it.entryId == "second" && it.fadingOut?.entryId == "first" && it.positionMs >= 250 && it.playing
                        } }
                        assertEquals(1.0, middle.volume, "The volume control followed the fade instead of the user's volume")
                        assertTrue(requireNotNull(middle.fadingOut).playing)
                        assertNotEquals(middle.playbackId, middle.fadingOut?.playbackId)
                        engine.pause()
                        withTimeout(2_000) { engine.status.first { !it.playing && it.fadingOut?.playing == false } }
                        engine.setGaplessPlayback(false)
                        engine.setAudioEffects(AudioEffects())
                        delay(100) // Allow the native position poll to observe the completed pause.
                        val paused = engine.status.value
                        assertNotNull(paused.fadingOut, "Changing gapless while paused cancelled the crossfade")
                        delay(250)
                        assertEquals(paused.positionMs.toDouble(), engine.status.value.positionMs.toDouble(), 60.0)
                        assertEquals(requireNotNull(paused.fadingOut).positionMs.toDouble(), requireNotNull(engine.status.value.fadingOut).positionMs.toDouble(), 60.0)
                        engine.play()
                        engine.setGaplessPlayback(true)
                        engine.setVolume(0.8)
                        // Each player polls separately, so the outgoing track may report resuming one poll later.
                        val resumed = withTimeout(2_000) { engine.status.first {
                            it.playing && it.volume == 0.8 && it.fadingOut?.playing != false
                        } }
                        assertTrue(resumed.fadingOut?.playing == true, "Changing gapless interrupted the audible crossfade")
                        engine.setVolume(1.0)
                    }
                    withTimeout(10_000) { engine.status.first { it.entryId == "second" && it.ended } }
                    delay(600)
                } finally {
                    engine.close()
                    capture.destroy()
                    if (!capture.waitFor(2, TimeUnit.SECONDS)) { capture.destroyForcibly(); capture.waitFor(2, TimeUnit.SECONDS) }
                }
                val bytes = Files.readAllBytes(output)
                val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(bytes.size / 8) { samples.float.also { samples.float } }
            }
            val gapless = render("gapless", 0, false)
            val faded = render("faded", 1_000, true)
            fun duration(samples: FloatArray): Double {
                val first = samples.indexOfFirst { abs(it) > 0.0001 }
                val last = samples.indexOfLast { abs(it) > 0.0001 }
                assertTrue(first >= 0 && last > first)
                return (last - first) / 48_000.0
            }
            assertEquals(6.0, duration(gapless), 0.2)
            assertEquals(5.35, duration(faded), 0.3)
            val windows = (0 until faded.size - 9_600 step 4_800).map { start ->
                amplitude(faded, start, 440) to amplitude(faded, start, 660)
            }
            assertTrue(windows.any { it.first > 0.025 && it.second > 0.025 }, "The two tracks never overlapped")
            assertTrue(windows.any { it.first > 0.05 && it.second < 0.005 })
            assertTrue(windows.any { it.second > 0.05 && it.first < 0.005 })
            val first = faded.indexOfFirst { abs(it) > 0.0001 }
            val last = faded.indexOfLast { abs(it) > 0.0001 }
            val quietWindows = (first until last - 960 step 960).count { start ->
                (start until start + 960).all { abs(faded[it]) < 0.00001 }
            }
            assertTrue(quietWindows >= 5, "Pausing left audible audio in the mix")
        } finally {
            if (command("pactl", "get-default-sink").trim() == sink) command("pactl", "set-default-sink", previousSink)
            module?.let { command("pactl", "unload-module", it) }
            directory.toFile().deleteRecursively()
        }
    }

    private fun command(vararg arguments: String): String {
        val process = ProcessBuilder(*arguments).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Audio fixture command timed out")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            return output
        } finally { process.destroyForcibly() }
    }

    private fun amplitude(samples: FloatArray, start: Int, frequency: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        for (frame in start until start + 9_600) {
            val angle = 2 * PI * frequency * frame / 48_000
            real += samples[frame] * cos(angle)
            imaginary += samples[frame] * sin(angle)
        }
        return 2 * hypot(real, imaginary) / 9_600
    }
}
