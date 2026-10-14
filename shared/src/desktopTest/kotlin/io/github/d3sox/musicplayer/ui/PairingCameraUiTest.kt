package io.github.d3sox.musicplayer.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PairingCameraUiTest {
    @Test fun aCapturedQrFrameIsDisplayedAndDeliveredOnceOnTheComposeThread() {
        val directory = Files.createTempDirectory("manida-scanner-ui")
        val invitation = "manida://pair/fixture-invitation"
        val matrix = pairingQrMatrix(invitation)
        val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val scale = 360 / matrix.size
        val left = (640 - matrix.size * scale) / 2
        val top = (480 - matrix.size * scale) / 2
        for (y in 0 until 480) for (x in 0 until 640) {
            val mx = (x - left) / scale; val my = (y - top) / scale
            val black = x >= left && y >= top && mx in 0 until matrix.size && my in 0 until matrix.size && matrix.modules[my * matrix.size + mx]
            image.setRGB(x, y, if (black) 0 else 0xFFFFFF)
        }
        val file = directory.resolve("qr.png").toFile()
        ImageIO.write(image, "png", file)
        var capture: DesktopCameraCapture? = null
        try {
            runComposeUiTest {
                var owner: Thread? = null
                var calls = 0
                var scanned: String? = null
                setContent {
                    SideEffect { owner = Thread.currentThread() }
                    DesktopPairingCameraPreview(listOf(DesktopCamera("fixture", "Fixture camera")), Modifier,
                        onScanned = { assertSame(owner, Thread.currentThread()); calls++; scanned = it },
                        onError = { fail(it) }, openCamera = {
                            DesktopCameraCapture(listOf("-re", "-loop", "1", "-i", file.path)).also { capture = it }
                        })
                }
                waitUntil(timeoutMillis = 5_000) { scanned != null }
                assertEquals(invitation, scanned)
                onNodeWithContentDescription("Pairing camera preview").assertIsDisplayed()
                waitForIdle()
                assertEquals(1, calls)
                waitUntil(timeoutMillis = 3_000) { capture?.alive == false }
            }
        } finally { capture?.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun cameraErrorsAreReportedOnTheComposeThreadAndAnotherCameraCanBeSelected() = runComposeUiTest {
        var owner: Thread? = null
        var capture: DesktopCameraCapture? = null
        var error by mutableStateOf<String?>(null)
        setContent {
            SideEffect { owner = Thread.currentThread() }
            DesktopPairingCameraPreview(listOf(DesktopCamera("broken", "Unavailable camera"), DesktopCamera("good", "Working camera")), Modifier,
                onScanned = { fail("The fixture contains no QR code") },
                onError = { assertSame(owner, Thread.currentThread()); error = it }, openCamera = { selected ->
                    if (selected == "broken") throw IllegalStateException("Camera unavailable")
                    DesktopCameraCapture(listOf("-re", "-f", "lavfi", "-i", "color=white:size=640x480:rate=10")).also { capture = it }
                })
            error?.let { Text(it) }
        }
        waitUntil(timeoutMillis = 5_000) { error != null }
        onNodeWithText(requireNotNull(error)).assertIsDisplayed()
        onNodeWithContentDescription("Camera").performClick()
        onNodeWithText("Working camera").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Pairing camera preview").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(requireNotNull(capture).alive)
    }

    @Test fun closingTheScannerStopsCaptureBeforeAnyLateCallbacks() = runComposeUiTest {
        var visible by mutableStateOf(true)
        var capture: DesktopCameraCapture? = null
        setContent {
            if (visible) DesktopPairingCameraPreview(listOf(DesktopCamera("fixture", "Fixture camera")), Modifier,
                onScanned = { fail("The fixture contains no QR code") }, onError = { fail(it) }, openCamera = {
                    DesktopCameraCapture(listOf("-re", "-f", "lavfi", "-i", "color=white:size=640x480:rate=10")).also { capture = it }
                })
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Pairing camera preview").fetchSemanticsNodes().isNotEmpty() }
        runOnIdle { visible = false }
        waitUntil(timeoutMillis = 3_000) { capture?.alive == false }
    }
}
