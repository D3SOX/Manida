package me.d3sox.manida

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.ListenableFuture
import io.github.d3sox.musicplayer.playback.AndroidPlaybackEngine
import io.github.d3sox.musicplayer.playback.AndroidSessionPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.d3sox.musicplayer.playback.PlayableTrack
import io.github.d3sox.musicplayer.playback.SessionPlaybackCommands
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidSessionPrivacyTest {
    @Test
    fun aPendingQueueLoadAlsoKeepsLocationsAndSourceDetailsOutOfThePublicPlayerState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "pending-private-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            instrumentation.runOnMainSync {
                val player = AndroidSessionPlayer(context)
                try {
                    player.setMediaItem(MediaItem.Builder().setMediaId("opaque-entry").setUri(file.toURI().toString())
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("Pending fixture")
                            .setExtras(Bundle().apply { putString("sourceId", "private-source") }).build()).build())
                    val item = requireNotNull(player.currentMediaItem)
                    assertEquals("Pending fixture", item.mediaMetadata.title.toString())
                    assertNull("The pending timeline exposed the file location", item.localConfiguration)
                    assertNull(item.mediaMetadata.extras?.getString("sourceId"))
                } finally { player.release() }
            }
        } finally { file.delete() }
    }

    @Test
    fun thePublicTimelineContainsMetadataWithoutFileLocationsOrPrivateSourceIdentifiers() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "private-source-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                (context.applicationContext as MusicApplication).controller.reload().join()
                val engine = AndroidPlaybackEngine(context, PlaybackService::class.java)
                lateinit var connection: ListenableFuture<MediaController>
                instrumentation.runOnMainSync { connection = MediaController.Builder(context,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
                val reader = connection.get(10, TimeUnit.SECONDS)
                try {
                    engine.load(PlayableTrack("private-track", file.toURI().toString(), title = "Private fixture",
                        artist = "Fixture Artist", entryId = "opaque-entry", sourceId = "private-source"))
                    withTimeout(10_000) { engine.status.first { it.playing && it.entryId == "opaque-entry" } }
                    withTimeout(5_000) {
                        var ready = false
                        while (!ready) {
                            instrumentation.runOnMainSync { ready = reader.currentMediaItem?.mediaId == "opaque-entry" }
                            if (!ready) delay(25)
                        }
                    }
                    lateinit var response: ListenableFuture<SessionResult>
                    instrumentation.runOnMainSync {
                        val item = requireNotNull(reader.currentMediaItem)
                        assertEquals("Private fixture", item.mediaMetadata.title.toString())
                        assertNull(item.localConfiguration)
                        assertNull(item.mediaMetadata.extras?.getString("trackId"))
                        assertNull(item.mediaMetadata.extras?.getString("sourceId"))
                        response = reader.sendCustomCommand(SessionPlaybackCommands.command(SessionPlaybackCommands.STATUS), Bundle.EMPTY)
                    }
                    val reply = response.get(5, TimeUnit.SECONDS)
                    assertEquals(SessionResult.RESULT_SUCCESS, reply.resultCode)
                    assertEquals("private-source", SessionPlaybackCommands.decodeStatus(reply.extras).sourceId)
                    assertFalse(requireNotNull(reply.extras.getString("status")).contains(file.absolutePath))
                } finally {
                    engine.pause(); engine.close()
                    instrumentation.runOnMainSync { MediaController.releaseFuture(connection) }
                }
            }
        } finally { file.delete() }
    }
}
