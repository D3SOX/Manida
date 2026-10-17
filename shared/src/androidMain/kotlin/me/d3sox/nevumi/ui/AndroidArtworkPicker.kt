package me.d3sox.nevumi.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberArtworkPicker(): ArtworkPicker {
    val resolver = LocalContext.current.contentResolver
    var pending by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pending?.complete(it) }
    return remember(resolver, launcher) {
        ArtworkPicker {
            val answer = CompletableDeferred<Uri?>()
            try {
                withContext(Dispatchers.Main) {
                    check(pending == null) { "An image picker is already open" }
                    pending = answer
                    launcher.launch(arrayOf("image/png", "image/jpeg"))
                }
                answer.await()?.let { uri -> withContext(Dispatchers.IO) {
                    requireNotNull(resolver.openInputStream(uri)) { "The selected image is unavailable" }.use(::readArtwork)
                } }
            } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { if (pending === answer) pending = null } }
        }
    }
}
