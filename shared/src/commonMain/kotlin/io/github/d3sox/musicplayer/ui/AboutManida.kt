package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import io.github.d3sox.musicplayer.resources.Res
import io.github.d3sox.musicplayer.resources.manida_logo
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun AboutManida(dismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val source = "https://github.com/D3SOX/Manida"
    var showLicense by remember { mutableStateOf(false) }
    var license by remember { mutableStateOf("") }
    LaunchedEffect(showLicense) {
        if (showLicense) license = Res.readBytes("files/LICENSE").decodeToString()
    }
    if (showLicense) {
        SettingsScreen(onDismissRequest = { showLicense = false }, title = { Text("License") },
            text = { Text(license, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) })
        return
    }
    SettingsScreen(onDismissRequest = dismiss, title = { Text("About Manida") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(Res.drawable.manida_logo), "Manida logo", Modifier.size(144.dp))
            Text("Manida", style = MaterialTheme.typography.headlineLarge)
            Text("Local music and your music server in one library.", textAlign = TextAlign.Center)
            Text("AGPL-3.0-only", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { uriHandler.openUri(source) }, Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(AppIcon.CODE.vector, null); Spacer(Modifier.width(8.dp)); Text("Source code")
            }
            OutlinedButton(onClick = { showLicense = true }, Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(AppIcon.LICENSE.vector, null); Spacer(Modifier.width(8.dp)); Text("View license")
            }
        }
    }, confirmButton = { PageAction("Done", onClick = dismiss) })
}
