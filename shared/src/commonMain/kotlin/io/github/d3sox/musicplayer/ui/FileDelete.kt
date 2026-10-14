package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.app.FileEditTarget
import io.github.d3sox.musicplayer.library.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FileDelete(track: Track, controller: MusicController, target: FileEditTarget = FileEditTarget.LOCAL, dismiss: () -> Unit) {
    var name by remember(track.id) { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(track.id) {
        try { name = controller.musicFileName(track.id, target) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { loadError = error.message ?: "The music file is unavailable" }
    }
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text(if (target == FileEditTarget.SERVER) "Delete server file?" else "Delete local file?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            name?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(if (target == FileEditTarget.SERVER) "This removes the music file from your server. Manida keeps the original so you can undo this deletion."
                else "This removes the local music file. Manida keeps the original so you can undo this deletion.")
            if (saving || (name == null && loadError == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
            (loadError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = name != null && !saving, onClick = {
            saving = true
            scope.launch {
                controller.deleteFile(track.id, target).join()
                saving = false
                if (controller.state.value.error == null) dismiss()
            }
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    }, dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text("Cancel") } })
}
