package io.github.d3sox.musicplayer.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberFolderPicker(): FolderPicker {
    var pending by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { pending?.complete(it) }
    return remember(launcher) {
        FolderPicker {
            val answer = CompletableDeferred<Uri?>()
            try {
                withContext(Dispatchers.Main) {
                    check(pending == null) { "A folder picker is already open" }
                    pending = answer
                    launcher.launch(Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic"))
                }
                answer.await()?.let { uri ->
                    require(uri.authority == "com.android.externalstorage.documents") { "Choose a folder on your device or SD card" }
                    val document = DocumentsContract.getTreeDocumentId(uri)
                    val volume = document.substringBefore(':')
                    val path = document.substringAfter(':', "").trim('/')
                    val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
                    if (path.isEmpty()) root else "$root/$path"
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { if (pending === answer) pending = null }
            }
        }
    }
}
