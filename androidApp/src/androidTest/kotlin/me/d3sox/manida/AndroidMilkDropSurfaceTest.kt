package me.d3sox.manida

import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.ui.MilkDropSurface
import io.github.d3sox.musicplayer.visualizer.MilkDropLibrary
import io.github.d3sox.musicplayer.visualizer.VisualizerPcm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMilkDropSurfaceTest {
    @Test fun reattachingAResumedSurfaceRestartsItsAudioSubscription() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val parent = activity.findViewById<FrameLayout>(android.R.id.content)
                val surface = MilkDropSurface(activity, MilkDropLibrary(emptyList(), app.cacheDir),
                    -1, false, emptyList(), {}, {})
                try {
                    parent.addView(surface, FrameLayout.LayoutParams(100, 100))
                    surface.setPlaying(true)
                    surface.resumeRendering()
                    assertTrue(VisualizerPcm.enabled)
                    parent.removeView(surface)
                    assertFalse(VisualizerPcm.enabled)
                    parent.addView(surface, FrameLayout.LayoutParams(100, 100))
                    assertTrue("Reattachment must resume audio while the activity stays resumed", VisualizerPcm.enabled)
                    surface.pauseRendering()
                    assertFalse(VisualizerPcm.enabled)
                    parent.removeView(surface)
                    parent.addView(surface, FrameLayout.LayoutParams(100, 100))
                    assertFalse("A paused surface must stay paused after reattachment", VisualizerPcm.enabled)
                } finally {
                    surface.release()
                    parent.removeView(surface)
                    assertFalse(VisualizerPcm.enabled)
                }
            }
        }
    }
}
