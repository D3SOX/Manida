package io.github.d3sox.musicplayer

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.d3sox.musicplayer.playback.AndroidPlaybackEngine
import io.github.d3sox.musicplayer.playback.PlayableTrack
import io.github.d3sox.musicplayer.playback.RepeatMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidCrossfadeFocusTest {
    @Test
    fun transientAudioFocusLossPausesBothTracksAndFocusGainResumesTheSameTransition() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val interruption = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener {}.build()
        val file = File(context.cacheDir, "focus-crossfade-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                (context.applicationContext as MusicApplication).controller.reload().join()
                val engine = AndroidPlaybackEngine(context, PlaybackService::class.java)
                try {
                    engine.setCrossfadeDuration(1_000)
                    withTimeout(10_000) { engine.status.first { it.crossfadeDurationMs == 1_000L } }
                    engine.setRepeat(RepeatMode.OFF)
                    engine.loadQueue(listOf(PlayableTrack("song", file.toURI().toString(), entryId = "first"),
                        PlayableTrack("song", file.toURI().toString(), entryId = "next")), positionMs = 1_000)
                    val overlap = withTimeout(10_000) { engine.status.first { it.playing && it.positionMs >= 200 && it.fadingOut?.playing == true } }
                    instrumentation.runOnMainSync { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(interruption)) }
                    withTimeout(3_000) { engine.status.first { !it.playing && it.fadingOut?.playing == false } }
                    delay(150)
                    val paused = engine.status.value
                    assertTrue("A transient interruption lost the user's playback intent", paused.playWhenReady)
                    assertEquals(overlap.playbackId, paused.playbackId)
                    assertEquals(overlap.fadingOut?.playbackId, paused.fadingOut?.playbackId)
                    delay(200)
                    assertEquals(paused.positionMs.toDouble(), engine.status.value.positionMs.toDouble(), 75.0)
                    assertEquals(requireNotNull(paused.fadingOut).positionMs.toDouble(), requireNotNull(engine.status.value.fadingOut).positionMs.toDouble(), 75.0)
                    instrumentation.runOnMainSync { audio.abandonAudioFocusRequest(interruption) }
                    val resumed = withTimeout(3_000) { engine.status.first { it.playing && it.fadingOut?.playing == true } }
                    assertEquals(overlap.playbackId, resumed.playbackId)
                    assertEquals(overlap.fadingOut?.playbackId, resumed.fadingOut?.playbackId)
                    withTimeout(5_000) { engine.status.first { it.ended } }
                    Unit
                } finally {
                    instrumentation.runOnMainSync { audio.abandonAudioFocusRequest(interruption) }
                    engine.setCrossfadeDuration(0); engine.pause(); engine.close()
                }
            }
        } finally { file.delete() }
    }
}
