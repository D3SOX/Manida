package io.github.d3sox.musicplayer

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.playback.AndroidPlaybackEngine
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.settings.MusicPreferences
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidPlaybackSettingsRestoreTest {
    @Test
    fun speedPitchAndCrossfadeRestoreTogetherFromSavedPreferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MusicApplication
        val name = "restore-audio-${UUID.randomUUID()}.db"
        val store = openMusicStore(context, name)
        val preferences = MusicPreferences(store)
        preferences.update { it.copy(speed = 1.5, pitch = 2.0, crossfadeDurationMs = 4_000) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                app.controller.reload().join()
                val controller = MusicController(MusicLibrary(store), PlaybackQueue(store),
                    AndroidPlaybackEngine(context, PlaybackService::class.java), preferences = preferences)
                try {
                    try { withTimeout(8_000) { controller.state.first { state ->
                        !state.loading && state.playback.speed in 1.49..1.51 && state.playback.pitch in 1.99..2.01 &&
                            state.playback.crossfadeDurationMs == 4_000L
                    } } } catch (failure: Exception) {
                        throw AssertionError("Restored audio settings: ${controller.state.value.playback.let { listOf(it.speed, it.pitch, it.crossfadeDurationMs, it.error) }}", failure)
                    }
                    assertEquals(1.5, controller.state.value.preferences.speed, 0.001)
                    assertEquals(2.0, controller.state.value.preferences.pitch, 0.001)
                } finally { controller.closeAndJoin() }
            }
        } finally {
            app.controller.engine.setCrossfadeDuration(0)
            app.controller.setSpeed(1.0).join()
            app.controller.setPitch(1.0).join()
            store.close()
            context.deleteDatabase(name)
        }
    }
}
