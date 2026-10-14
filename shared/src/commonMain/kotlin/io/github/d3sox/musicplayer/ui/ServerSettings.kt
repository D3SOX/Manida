package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.d3sox.musicplayer.app.AppState
import io.github.d3sox.musicplayer.app.MusicController

@Composable
internal fun ServerSettings(state: AppState, controller: MusicController, onClose: () -> Unit) {
    var address by remember { mutableStateOf(state.server?.baseUrl.orEmpty()) }
    var username by remember { mutableStateOf(state.server?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, state.server, state.error) {
        if (submitted && !state.loading && state.error == null && state.server != null) onClose()
    }
    SettingsScreen(onDismissRequest = onClose, title = { Text("Music server") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect to Navidrome or a Subsonic-compatible server.")
            OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Server address") }, leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true)
            OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Username") }, leadingIcon = { Icon(AppIcon.ARTIST.vector, null) }, singleLine = true)
            OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, leadingIcon = { Icon(AppIcon.LOCK.vector, null) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            if (state.server != null) PageAction("Refresh library", AppIcon.LIBRARY, enabled = !state.loading, onClick = {
                submitted = true
                controller.refreshServer()
            })
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        PageAction("Connect", AppIcon.CLOUD, enabled = !state.loading, onClick = {
            submitted = true
            controller.connectServer(address, username, password)
        })
    }, dismissButton = { PageAction("Cancel", AppIcon.CLOSE, secondary = true, onClick = onClose) })
}
