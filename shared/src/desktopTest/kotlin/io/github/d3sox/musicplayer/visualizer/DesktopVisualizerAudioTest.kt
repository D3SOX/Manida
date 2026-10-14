package io.github.d3sox.musicplayer.visualizer

import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.playback.PlayableTrack
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.sin
import kotlin.test.*

class DesktopVisualizerAudioTest {
    @Test fun theRealAudioMonitorCapturesOurPlayerAndExcludesOtherApplications() = runBlocking {
        val directory = Files.createTempDirectory("milkdrop-audio-isolation").toFile()
        val sink = "manida-test-${java.util.UUID.randomUUID()}"
        val module = ProcessBuilder("pactl", "load-module", "module-null-sink", "sink_name=$sink").start()
        val moduleId = module.inputStream.bufferedReader().readText().trim()
        check(module.waitFor() == 0 && moduleId.toIntOrNull() != null) { "A PulseAudio-compatible service is required for this test" }
        var other: Process? = null
        try {
            val rate = 44100
            val frames = rate * 20
            val raw = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames * 2) { raw.putFloat(.8f) }
            val unrelated = directory.resolve("other.raw").also { it.writeBytes(raw.array()) }
            other = ProcessBuilder("pacat", "--playback", "--raw", "--device=$sink", "--format=float32le", "--rate=44100", "--channels=2")
                .redirectInput(unrelated).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val wav = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
                .put("data".toByteArray()).putInt(frames * 4)
            repeat(frames) { frame ->
                val sample = (sin(frame * 2 * Math.PI * 440 / rate) * 8000).toInt().toShort()
                wav.putShort(sample).putShort(sample)
            }
            val music = directory.resolve("our.wav").also { it.writeBytes(wav.array()) }
            val server = ProcessBuilder("pactl", "info").start().inputStream.bufferedReader().readText()
            val drivers = if (server.contains("PipeWire")) listOf("pulse", "pipewire") else listOf("pulse")
            for (driver in drivers) VisualizerPcm.subscribe().use {
                MpvPlaybackEngine(driver, audioDevice = "$driver/$sink").use { player ->
                    player.load(PlayableTrack("our", music.path))
                    val monitor = DesktopVisualizerAudio()
                    try {
                        val samples = withTimeoutOrNull(10_000) {
                            var pcm = floatArrayOf()
                            while (pcm.isEmpty() || pcm.none { kotlin.math.abs(it) > .05f }) { delay(20); pcm = VisualizerPcm.consume() }
                            pcm
                        }
                        assertNotNull(samples, "$driver audio did not reach the visualizer: ${player.status.value.error}; ${monitor.notice.value}")
                        assertNull(monitor.notice.value)
                        assertTrue(samples.max() > .1f && samples.min() < -.1f, "$driver playback PCM must reach MilkDrop")
                        assertTrue(kotlin.math.abs(samples.average()) < .05, "Other applications' constant audio must be excluded")
                        assertTrue(samples.max() < .4f, "The mixed system output must not reach MilkDrop")
                    } finally { monitor.closeAndJoin() }
                }
            }
        } finally {
            other?.destroyForcibly()
            ProcessBuilder("pactl", "unload-module", moduleId).start().waitFor()
            directory.deleteRecursively()
        }
    }
}
