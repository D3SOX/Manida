package me.d3sox.nevumi.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class AudioLevelPlaybackTest {
    @Test fun artworkEnergyFollowsTheDecodedAudioAndStopsWhenPausedOrDisabled() = runBlocking {
        val file = Files.createTempFile("nevumi-beats", ".wav")
        val frames = 8_000 * 4
        val pcm = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames * 2)
        repeat(frames) { sample ->
            val amplitude = if (sample < 16_000) .1 else .8
            pcm.putShort((sin(2 * PI * 90 * sample / 8_000) * amplitude * 32_767).toInt().toShort())
        }
        Files.write(file, pcm.array())
        val engine = CrossfadePlaybackEngine { MpvPlaybackEngine("null") }
        try {
            engine.setAudioLevelEnabled(true)
            engine.load(PlayableTrack("beats", file.toString()))
            withTimeout(3_000) { engine.status.first { it.playing && it.positionMs in 350..700 } }
            val quiet = engine.audioLevel.value
            assertTrue(quiet in .04f.. .14f, "Quiet decoded audio must produce energy, got $quiet")
            withTimeout(4_000) { engine.status.first { it.playing && it.positionMs in 2_400..2_800 } }
            val loud = engine.audioLevel.value
            assertTrue(loud > quiet * 4, "The cover must respond to audio dynamics: quiet=$quiet loud=$loud")
            engine.pause()
            withTimeout(1_000) { engine.audioLevel.first { it == 0f } }
            engine.setAudioLevelEnabled(false)
            engine.play()
            withTimeout(1_000) { engine.status.first { it.playing } }
            delay(150)
            assertEquals(0f, engine.audioLevel.value)
        } finally { engine.close(); Files.deleteIfExists(file) }
    }
}
