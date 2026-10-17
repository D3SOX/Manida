package me.d3sox.nevumi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.floor

internal data class QrMatrix(val size: Int, val modules: BooleanArray)
internal expect fun pairingQrMatrix(invitation: String): QrMatrix

@Composable
internal fun PairingQr(invitation: String, modifier: Modifier = Modifier) {
    val matrix = remember(invitation) { pairingQrMatrix(invitation) }
    Canvas(modifier.sizeIn(maxWidth = 360.dp, maxHeight = 360.dp).fillMaxWidth().aspectRatio(1f)
        .background(Color.White).semantics { contentDescription = "Pairing QR code" }) {
        val module = floor(size.width / matrix.size)
        val inset = (size.width - module * matrix.size) / 2
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) if (matrix.modules[y * matrix.size + x]) {
            drawRect(Color.Black, Offset(inset + x * module, inset + y * module), Size(module, module))
        }
    }
}

@Composable
internal expect fun PairingCameraPreview(modifier: Modifier, onScanned: (String) -> Unit, onError: (String) -> Unit)

internal val LocalPairingCamera = staticCompositionLocalOf<@Composable (Modifier, (String) -> Unit, (String) -> Unit) -> Unit> {
    { modifier, scanned, error -> PairingCameraPreview(modifier, scanned, error) }
}

@Composable
internal fun PairingScanner(onScanned: (String) -> Unit, dismiss: () -> Unit, useManualCode: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Scan pairing QR code") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Point the camera at the pairing invitation shown on your other device.")
            LocalPairingCamera.current(Modifier.fillMaxWidth().heightIn(max = 320.dp), onScanned) { error = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = useManualCode) { Text("Use manual code") } })
}
