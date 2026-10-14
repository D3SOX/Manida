package io.github.d3sox.musicplayer

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidLocalChangesTest {
    @Test
    fun newDeviceMusicAppearsWithoutRefreshingOrMovingThePausedPlayhead() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val controller = (context.applicationContext as MusicApplication).controller
        val resolver = context.contentResolver
        val folder = "Music/Manida-watch-${UUID.randomUUID()}"
        val created = mutableListOf<Uri>()
        fun add(asset: String): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, asset)
                put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
            created += uri
            instrumentation.context.assets.open(asset).use { input ->
                requireNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var phase = "seek paused song"
        try {
            val initial = add("fixture.flac")
            controller.importSource("device").join()
            val track = controller.state.value.tracks.single { it.sources.any { source -> source.location == initial.toString() } }
            controller.playTrack(track.id, listOf(track.id)).join()
            withTimeout(5_000) { controller.state.first { it.playback.trackId == track.id && it.playback.durationMs >= 2_000 } }
            controller.pause().join()
            controller.seek(1_000).join()
            withTimeout(5_000) { controller.state.first { !it.playback.playing && it.playback.positionMs in 990..1100 } }
            val entry = controller.queue.snapshot().currentEntryId
            phase = "observe newly added music"
            val added = add("fixture.mp3")
            val updated = withTimeout(5_000) { controller.state.first { state ->
                state.tracks.any { it.sources.any { source -> source.location == added.toString() } }
            } }
            assertEquals("Long Way Home", updated.tracks.single { it.sources.any { source -> source.location == added.toString() } }.metadata.title)
            assertEquals(entry, updated.playback.entryId)
            assertEquals(false, updated.playback.playing)
            assertTrue(updated.playback.positionMs in 990..1100)
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out during $phase: playback=${controller.state.value.playback}, error=${controller.state.value.error}, sources=${controller.state.value.tracks.flatMap { it.sources }}", error)
        } finally {
            controller.pause().join(); scenario.close()
            created.forEach { resolver.delete(it, null, null) }
        }
    }
}
