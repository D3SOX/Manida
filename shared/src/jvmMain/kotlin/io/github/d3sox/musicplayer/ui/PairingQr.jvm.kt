package io.github.d3sox.musicplayer.ui

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.multi.qrcode.QRCodeMultiReader

internal actual fun pairingQrMatrix(invitation: String): QrMatrix {
    require(isPairingInvitation(invitation)) { "Invalid pairing invitation" }
    val matrix = QRCodeWriter().encode(invitation, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4,
            EncodeHintType.CHARACTER_SET to "UTF-8"))
    return QrMatrix(matrix.width, BooleanArray(matrix.width * matrix.height) { matrix[it % matrix.width, it / matrix.width] })
}

internal fun isPairingInvitation(text: String) = text.startsWith("manida://pair/") && text.length in 14..4096

internal fun decodePairingQr(luminance: ByteArray, width: Int, height: Int): String? {
    val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
    val first = try { MultiFormatReader().decode(bitmap, hints).text.takeIf(::isPairingInvitation) }
        catch (_: ReaderException) { null }
    // Try other finder-pattern combinations when a dense QR has misleading patterns.
    return first ?: try { QRCodeMultiReader().decodeMultiple(bitmap, hints).firstNotNullOfOrNull { it.text.takeIf(::isPairingInvitation) } }
        catch (_: ReaderException) { null }
}

// Camera planes may have padding between rows or pixels.
internal fun cameraLuminance(bytes: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
    val start = bytes.position()
    return ByteArray(width * height) { index -> bytes.get(start + (index / width) * rowStride + (index % width) * pixelStride) }
}
