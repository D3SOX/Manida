package me.d3sox.manida

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.settings.ColorMode
import io.github.d3sox.musicplayer.settings.ThemeMode
import io.github.d3sox.musicplayer.settings.MusicPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidArtworkUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun aLocalEmbeddedCoverReachesThePublicAndroidMediaControls() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val file = java.io.File(app.cacheDir, "session-cover-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture-cover.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        var reader: androidx.media3.session.MediaController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                app.controller.importSource(file.path).join()
                val track = app.controller.state.value.songAt(file.canonicalPath)!!
                lateinit var connection: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>
                instrumentation.runOnMainSync { connection = androidx.media3.session.MediaController.Builder(app,
                    androidx.media3.session.SessionToken(app, android.content.ComponentName(app, PlaybackService::class.java))).buildAsync() }
                val controls = connection.get(10, java.util.concurrent.TimeUnit.SECONDS)
                reader = controls
                app.controller.playTrack(track.id, listOf(track.id, track.id, track.id), 1).join()
                app.controller.pause().join()
                var cover: ByteArray? = null
                kotlinx.coroutines.withTimeout(5_000) {
                    while (cover == null) {
                        instrumentation.runOnMainSync { cover = controls.currentMediaItem?.mediaMetadata?.artworkData }
                        kotlinx.coroutines.delay(25)
                    }
                }
                val notifications = app.getSystemService(android.app.NotificationManager::class.java)
                kotlinx.coroutines.withTimeout(5_000) {
                    while (notifications.activeNotifications.none { it.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) })
                        kotlinx.coroutines.delay(25)
                }
                val notification = notifications.activeNotifications.first { it.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) }.notification
                assertEquals(R.drawable.ic_launcher_monochrome, notification.smallIcon.resId)
                assertEquals(listOf("Previous", "Play", "Next"), notification.actions.map { it.title.toString() })
                val bytes = requireNotNull(cover)
                org.junit.Assert.assertTrue(bytes.size < 256 * 1024)
                val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                org.junit.Assert.assertTrue(kotlin.math.abs(android.graphics.Color.red(pixel) - 224) < 10)
                org.junit.Assert.assertTrue(kotlin.math.abs(android.graphics.Color.green(pixel) - 80) < 10)
                instrumentation.runOnMainSync { org.junit.Assert.assertNull(controls.currentMediaItem?.mediaMetadata?.artworkUri) }
            }
        } finally {
            app.controller.pause().join()
            instrumentation.runOnMainSync { reader?.release() }
            file.delete()
        }
    }

    @Test
    fun aDeviceFlacCoverIsImportedAndDisplayedByTheNativePlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Orchestrator's data-clear broadcast can reach MediaStore after the next test starts.
        for ((command, completed) in listOf(
            "am wait-for-broadcast-idle" to "All broadcast queues are idle!",
            "content call --uri content://media --method wait_for_idle" to "Result:"
        )) {
            val output = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
            check(completed in output) { output }
        }
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val original = runBlocking { MusicPreferences(app.store).load() }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${app.packageName} $permission")).use { it.readBytes() }
        val resolver = app.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture-cover.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MusicPlayer-Cover-${UUID.randomUUID()}")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        try {
            instrumentation.context.assets.open("fixture-cover.flac").use { input ->
                requireNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            runBlocking {
                app.controller.importSource("device").join()
                app.controller.updatePreferences { it.copy(themeMode = ThemeMode.LIGHT, colors = ColorMode.ARTWORK) }.join()
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { app.controller.playTrack(app.controller.state.value.songAt(uri.toString())!!.id, listOf(app.controller.state.value.songAt(uri.toString())!!.id), 0).join() }
                compose.waitUntil(5_000) { app.controller.state.value.playback.playing }
                runBlocking { app.controller.togglePlayback().join() }
                compose.onNodeWithTag("mini-player").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithContentDescription("Album artwork: Northern Light").fetchSemanticsNodes().isNotEmpty()
                }
                val pixels = compose.onNodeWithContentDescription("Album artwork: Northern Light").captureToImage().toPixelMap()
                assertEquals(Color(0xFFE05040), pixels[pixels.width / 2, pixels.height / 2])
            }
        } finally {
            resolver.delete(uri, null, null)
            runBlocking { app.controller.updatePreferences { original }.join() }
        }
    }
}
