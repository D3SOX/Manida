package me.d3sox.nevumi.playback

import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackSource
import kotlinx.coroutines.runBlocking
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.math.sin
import kotlin.test.*

class AudioWaveformsTest {
    @Test fun shortTransientsDoNotFlattenTheWaveformOfQuietAndLoudMusic() = runBlocking {
        val directory = Files.createTempDirectory("waveform-dynamics").toFile()
        val file = directory.resolve("transients.wav")
        DataOutputStream(file.outputStream()).use { output ->
            fun word(value: Int) = output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
            fun integer(value: Int) = output.writeInt(Integer.reverseBytes(value))
            output.writeBytes("RIFF"); integer(36 + 6_400 * 2); output.writeBytes("WAVEfmt ")
            integer(16); word(1); word(1); integer(8_000); integer(16_000); word(2); word(16)
            output.writeBytes("data"); integer(6_400 * 2)
            repeat(6_400) { sample ->
                val signal = if (sample % 32 == 0) .95 else sin(sample * 2 * Math.PI * 440 / 8_000) * (if (sample < 3_200) .05 else .8)
                word((signal * 32_767).toInt())
            }
        }
        try {
            val waveform = FfmpegAudioWaveforms(directory.resolve("cache").path)
                .load(TrackSource("transients", SourceKind.LOCAL, file.path), 800)
            val quiet = waveform.subList(8, 48).average()
            val loud = waveform.subList(80, 120).average()
            assertTrue(loud > quiet * 2.5, "Waveform must show musical dynamics even when brief peaks are equally loud: $quiet / $loud")
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun supportedFormatsShowTheActualQuietAndLoudPartsOfTheAudio() = runBlocking {
        val directory = Files.createTempDirectory("audio-waveforms").toFile()
        val wav = directory.resolve("wave.wav")
        DataOutputStream(wav.outputStream()).use { output ->
            fun word(value: Int) = output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
            fun integer(value: Int) = output.writeInt(Integer.reverseBytes(value))
            output.writeBytes("RIFF"); integer(36 + 6_400 * 2); output.writeBytes("WAVEfmt ")
            integer(16); word(1); word(1); integer(8_000); integer(16_000); word(2); word(16)
            output.writeBytes("data"); integer(6_400 * 2)
            repeat(6_400) { sample ->
                val amplitude = if (sample < 3_200) .1 else .8
                word((sin(sample * 2 * Math.PI * 440 / 8_000) * amplitude * 32_767).toInt())
            }
        }
        try {
            for ((format, codec) in listOf("flac" to "flac", "mp3" to "libmp3lame")) {
                val conversion = ProcessBuilder("ffmpeg", "-v", "error", "-i", wav.path, "-c:a", codec, directory.resolve("wave.$format").path)
                    .redirectErrorStream(true).start()
                val error = conversion.inputStream.bufferedReader().readText()
                assertEquals(0, conversion.waitFor(), error)
            }
            val reader = FfmpegAudioWaveforms(directory.resolve("cache").path)
            for (format in listOf("wav", "mp3", "flac")) {
                val file = directory.resolve("wave.$format")
                val peaks = reader.load(TrackSource("local:$format", SourceKind.LOCAL, file.path,
                    modifiedAt = file.lastModified(), indexedSize = file.length()), 800)
                assertEquals(128, peaks.size, "$format must have real audio peaks")
                val quiet = peaks.subList(8, 48).average()
                val loud = peaks.subList(80, 120).average()
                assertTrue(loud > quiet * 4, "$format must preserve the loudness difference, quiet=$quiet loud=$loud")
                assertTrue(peaks.all { it.isFinite() && it in 0f..1f })
            }
        } finally { directory.deleteRecursively() }
    }
}
