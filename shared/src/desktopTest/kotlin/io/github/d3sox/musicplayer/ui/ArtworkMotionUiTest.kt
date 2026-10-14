package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.playback.*
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ArtworkMotionUiTest {
    @Test fun audioChangesMoveTheCoverSmoothlyAndSettleWithinOneTenthOfASecond() {
        val directory = Files.createTempDirectory("artwork-motion")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val source = Path.of(System.getProperty("musicplayer.testRoot"), "androidApp", "src", "androidTest", "assets", "waveform-long.flac").toString()
        val track = runBlocking { library.importTrack(TrackMetadata("Beats", durationMs = 600_000), TrackSource("beats", SourceKind.LOCAL, source)) }
        val decoder = MpvPlaybackEngine("null")
        val levels = MutableStateFlow(0f)
        val engine = object : PlaybackEngine by decoder {
            override val audioLevel = levels
            override fun setAudioLevelEnabled(enabled: Boolean) = Unit
        }
        val controller = MusicController(library, PlaybackQueue(store), engine)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(track.id).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                onNodeWithTag("mini-player").performClick()
                mainClock.autoAdvance = false
                mainClock.advanceTimeBy(1_200)
                fun width() = onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width
                val original = width()
                runOnIdle { levels.value = 1f }
                mainClock.advanceTimeByFrame()
                mainClock.advanceTimeByFrame()
                mainClock.advanceTimeBy(48)
                val middle = width()
                mainClock.advanceTimeBy(64)
                val settled = width()
                mainClock.advanceTimeBy(400)
                val final = width()
                assertTrue(middle > original && middle < final, "A beat should animate through intermediate sizes")
                assertTrue(kotlin.math.abs(settled - final) < .5f, "A cover must keep up with beats instead of trailing them: $settled / $final")
                runOnIdle { levels.value = 0f }
                mainClock.advanceTimeBy(160)
                assertTrue(kotlin.math.abs(width() - original) < .5f, "Silence returns to the normal cover size")
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
