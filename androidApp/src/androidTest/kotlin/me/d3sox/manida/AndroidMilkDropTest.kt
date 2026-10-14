package me.d3sox.manida

import android.opengl.EGL14
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.playback.ManidaAudioRenderersFactory
import io.github.d3sox.musicplayer.visualizer.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@UnstableApi
class AndroidMilkDropTest {
    @Test fun realPlaybackAudioDrivesBothCollectionsAndExternalTexturesOnAnOpenGlEsContext() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = File(context.cacheDir, "milkdrop-audio.wav")
        instrumentation.context.assets.open("fixture.wav").use { input -> audio.outputStream().use { input.copyTo(it) } }
        lateinit var player: ExoPlayer
        VisualizerPcm.subscribe().use {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context, ManidaAudioRenderersFactory(context)).build()
                player.volume = 0f
                player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                player.setMediaItem(MediaItem.fromUri(audio.toURI().toString()))
                player.prepare(); player.play()
            }
            try {
                val samples = withTimeout(10_000) {
                    var pcm = floatArrayOf()
                    while (pcm.isEmpty() || pcm.none { kotlin.math.abs(it) > .01f }) { delay(20); pcm = VisualizerPcm.consume() }
                    pcm
                }
                assertTrue(samples.size <= 2048)
                ProjectMNative.initialize()
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                assertTrue(EGL14.eglInitialize(display, null, 0, null, 0))
                val configurations = arrayOfNulls<android.opengl.EGLConfig>(1)
                val count = IntArray(1)
                assertTrue(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configurations, 0, 1, count, 0))
                assertEquals(1, count[0])
                val glContext = EGL14.eglCreateContext(display, configurations[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                val surface = EGL14.eglCreatePbufferSurface(display, configurations[0], intArrayOf(
                    EGL14.EGL_WIDTH, 320, EGL14.EGL_HEIGHT, 240, EGL14.EGL_NONE), 0)
                assertTrue(EGL14.eglMakeCurrent(display, surface, surface, glContext))
                val library = milkDropLibrary(File(context.cacheDir, "milkdrop-test-presets"))
                assertEquals(10347, library.presets.size)
                assertEquals(67, library.textures.listFiles().orEmpty().size)
                val presets = listOf(library.presets.first(),
                    library.presets.single { it.name == "Flexi + Rovastar - postcard from Fractopia" },
                    library.presets.single { it.name == "Phat_shifter - Angel" }) +
                    library.presets.filter { it.info.collection == "Cream of the Crop" && !it.info.category.startsWith("! Transition") }
                        .groupBy { it.info.category.substringBefore('/') }.values.map { it.first() }
                val visiblePresets = presets.take(3).toSet()
                try {
                    repeat(2) {
                        val handle = ProjectMNative.create(320, 240, false, library.textures.path)
                        try {
                            for (preset in presets) {
                                ProjectMNative.load(handle, preset.file.path, false)
                                repeat(24) { ProjectMNative.render(handle, samples, 320, 240); Thread.sleep(10) }
                                val pixels = ProjectMNative.pixels(handle)
                                assertEquals(320 * 240 * 4, pixels.size)
                                val colored = (pixels.indices step 4).count { index ->
                                    (pixels[index].toInt() and 255) + (pixels[index + 1].toInt() and 255) + (pixels[index + 2].toInt() and 255) > 30
                                }
                                // Beat-gated and feedback presets can stay black for this steady test tone.
                                if (preset in visiblePresets) assertTrue("${preset.name} must render the player's audio", colored > 50)
                            }
                        } finally { ProjectMNative.destroy(handle) }
                    }
                } finally {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, glContext); EGL14.eglTerminate(display)
                }
            } finally { instrumentation.runOnMainSync { player.release() }; audio.delete() }
        }
        assertFalse(VisualizerPcm.enabled)
    }
}
