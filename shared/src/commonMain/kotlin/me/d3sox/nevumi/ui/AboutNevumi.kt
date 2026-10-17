package me.d3sox.nevumi.ui

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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.nevumi_logo
import org.jetbrains.compose.resources.painterResource

@Composable
internal expect fun appVersion(): String

@Composable
internal fun AboutNevumi(dismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val source = "https://github.com/D3SOX/Nevumi"
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
    SettingsScreen(onDismissRequest = dismiss, title = { Text("About Nevumi") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(Res.drawable.nevumi_logo), "Nevumi logo", Modifier.size(144.dp))
            Text("Nevumi", style = MaterialTheme.typography.headlineLarge)
            Text("Version ${appVersion()}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
