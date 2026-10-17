package me.d3sox.nevumi

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.playback.AndroidSessionPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidSessionSourceSwitchTest {
    @Test
    fun changingTheSourceOfTheSameQueueEntryFinishesPreparingThePublicPlayer() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val files = (1..2).map { index ->
            File(context.cacheDir, "source-switch-$index-${UUID.randomUUID()}.flac").also { file ->
                instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }
        fun item(index: Int) = MediaItem.Builder().setMediaId("same-queue-entry").setUri(files[index].toURI().toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Source ${index + 1}")
                .setExtras(Bundle().apply { putString("trackId", "same-song"); putString("sourceId", "source-$index") }).build()).build()
        lateinit var player: AndroidSessionPlayer
        instrumentation.runOnMainSync { player = AndroidSessionPlayer(context); player.setMediaItem(item(0)); player.prepare() }
        suspend fun ready() = withTimeout(2_000) {
            var isReady = false
            while (!isReady) {
                instrumentation.runOnMainSync { isReady = player.playbackState == Player.STATE_READY && player.duration == 3_000L }
                if (!isReady) delay(25)
            }
        }
        try {
            ready()
            instrumentation.runOnMainSync { player.setMediaItem(item(1), 500); player.prepare() }
            ready()
            instrumentation.runOnMainSync {
                assertEquals("same-queue-entry", player.currentMediaItem?.mediaId)
                assertEquals("Source 2", player.currentMediaItem?.mediaMetadata?.title.toString())
                assertEquals(500L, player.currentPosition)
                assertNull(player.currentMediaItem?.localConfiguration)
            }
        } finally {
            instrumentation.runOnMainSync { player.release() }
            files.forEach { it.delete() }
        }
    }
}
