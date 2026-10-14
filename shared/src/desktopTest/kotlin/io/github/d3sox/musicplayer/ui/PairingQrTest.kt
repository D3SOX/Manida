package io.github.d3sox.musicplayer.ui

import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class PairingQrTest {
    private val invitation = "manida://pair/" + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(450).also { java.util.Random(42).nextBytes(it) })
    private fun image(code: String): BufferedImage {
        val matrix = pairingQrMatrix(code)
        val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val scale = 400 / matrix.size
        val left = (640 - matrix.size * scale) / 2
        val top = (480 - matrix.size * scale) / 2
        for (y in 0 until 480) for (x in 0 until 640) {
            val mx = (x - left) / scale; val my = (y - top) / scale
            val black = x >= left && y >= top && mx in 0 until matrix.size && my in 0 until matrix.size && matrix.modules[my * matrix.size + mx]
            image.setRGB(x, y, if (black) 0 else 0xFFFFFF)
        }
        return image
    }

    @Test fun invitationsDecodeFromWebcamFramesAndCameraPlanesWithPaddingAndRotation() {
        val image = image(invitation)
        assertEquals(invitation, image.pairingInvitation())
        val padded = ByteBuffer.allocate(480 * (640 * 2 + 32) + 17).apply { position(17) }
        for (y in 0 until 480) for (x in 0 until 640) padded.put(17 + y * (640 * 2 + 32) + x * 2, (image.getRGB(x, y) and 255).toByte())
        val pixels = cameraLuminance(padded, 640, 480, 640 * 2 + 32, 2)
        assertEquals(invitation, decodePairingQr(pixels, 640, 480))
        val rotated = ByteArray(pixels.size)
        for (y in 0 until 480) for (x in 0 until 640) rotated[x * 480 + 479 - y] = pixels[y * 640 + x]
        assertEquals(invitation, decodePairingQr(rotated, 480, 640))
        assertNull(decodePairingQr(ByteArray(640 * 480) { 255.toByte() }, 640, 480))
        assertFailsWith<IllegalArgumentException> { pairingQrMatrix("https://example.com/") }
    }

    @Test fun denseQrPatternsCanStillBeDecoded() {
        assertEquals("manida://pair/" + "YQ".repeat(300), image("manida://pair/" + "YQ".repeat(300)).pairingInvitation())
    }

    @Test fun ffmpegFramesUseTheRealDecoderAndClosingStopsCapture() {
        val directory = Files.createTempDirectory("manida-qr-camera")
        val file = directory.resolve("invitation.png").toFile()
        ImageIO.write(image(invitation), "png", file)
        val capture = DesktopCameraCapture(listOf("-re", "-loop", "1", "-i", file.path))
        try {
            assertEquals(invitation, capture.readFrame().pairingInvitation())
            assertTrue(capture.alive)
            capture.close()
            val deadline = System.nanoTime() + 3_000_000_000
            while (capture.alive && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(capture.alive, "Closing the scanner must stop webcam capture")
        } finally { capture.close(); directory.toFile().deleteRecursively() }
    }
}
