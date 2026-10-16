package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PlayerMarqueeUiTest {
    @Test fun handingTextToAnotherPlayerViewKeepsItsScrollPhase() = runComposeUiTest {
        var expanded by mutableStateOf(false)
        lateinit var shared: PlayerMarqueeState
        mainClock.autoAdvance = false
        setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            val scrolling = remember { PlayerMarqueeState(scope) }
            SideEffect { shared = scrolling }
            key(expanded) {
                Box(Modifier.width(200.dp).background(Color.White).testTag("marquee")) {
                    PlayerMarquee("ABCDEFGHIJKLMNOPQRSTUVWXYZ ".repeat(5), TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                        color = Color.Black, marqueeState = scrolling)
                }
            }
        } }
        mainClock.advanceTimeBy(100)
        val initial = onNodeWithTag("marquee").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(4_000)
        val before = onNodeWithTag("marquee").captureToImage().toPixelMap()
        val beforePosition = shared.position
        assertTrue(difference(initial, before) > .03)
        runOnIdle { expanded = true }
        mainClock.advanceTimeBy(32)
        val after = onNodeWithTag("marquee").captureToImage().toPixelMap()
        assertTrue(abs(shared.position - beforePosition) < .1f, "A handoff must preserve the scroll phase")
        // A fractional-pixel shift changes glyph antialiasing; compare against an actual reset as well.
        assertTrue(difference(before, after) < difference(initial, after) * .75,
            "Moving the title between player views must retain its visible position")
        mainClock.advanceTimeBy(500)
        assertTrue(difference(after, onNodeWithTag("marquee").captureToImage().toPixelMap()) > .02,
            "The title should continue scrolling immediately after the handoff")
    }

    @Test fun changingThePlayerViewportKeepsTheCurrentMarqueePosition() = runComposeUiTest {
        var width by mutableStateOf(200.dp)
        mainClock.autoAdvance = false
        setContent { MaterialTheme {
            Box(Modifier.width(width).background(Color.White).testTag("marquee")) {
                PlayerMarquee("ABCDEFGHIJKLMNOPQRSTUVWXYZ ".repeat(5), TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp), color = Color.Black)
            }
        } }
        mainClock.advanceTimeBy(100)
        val initial = onNodeWithTag("marquee").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(4_000)
        val scrolling = onNodeWithTag("marquee").captureToImage().toPixelMap()
        assertTrue(difference(initial, scrolling) > .03, "The test must reach a visibly scrolled title")
        runOnIdle { width = 201.dp }
        mainClock.advanceTimeBy(32)
        val resized = onNodeWithTag("marquee").captureToImage().toPixelMap()
        assertTrue(difference(scrolling, resized) < .025, "The marquee must retain its position when the player width changes")
    }

    @Test fun aMarqueeStopsAnimatingOnceItsTextFitsOrIsHidden() = runComposeUiTest {
        var width by mutableStateOf(200.dp)
        var active by mutableStateOf(true)
        lateinit var shared: PlayerMarqueeState
        mainClock.autoAdvance = false
        setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            val scrolling = remember { PlayerMarqueeState(scope) }
            SideEffect { shared = scrolling }
            Box(Modifier.requiredWidth(width)) {
                PlayerMarquee("ABCDEFGHIJKLMNOPQRSTUVWXYZ ".repeat(5), TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                    marqueeState = scrolling, active = active)
            }
        } }
        mainClock.advanceTimeBy(4_000)
        assertTrue(shared.position > 0f, "The test must reach a scrolling title")
        runOnIdle { width = 4_000.dp }
        mainClock.advanceTimeBy(100)
        val fitting = shared.position
        mainClock.advanceTimeBy(3_000)
        assertEquals(fitting, shared.position, "Text that fits must not keep requesting frames")
        runOnIdle { width = 200.dp; active = false }
        mainClock.advanceTimeBy(100)
        val hidden = shared.position
        mainClock.advanceTimeBy(3_000)
        assertEquals(hidden, shared.position, "Hidden text must not keep requesting frames")
    }

    private fun difference(first: PixelMap, second: PixelMap): Double {
        val width = minOf(128, first.width, second.width)
        val height = minOf(first.height, second.height)
        return (-2..2).minOf { shift ->
            var total = 0.0
            for (y in 0 until height) for (x in 18 until width) total += abs(first[x, y].red - second[x + shift, y].red)
            total / ((width - 18) * height)
        }
    }
}
