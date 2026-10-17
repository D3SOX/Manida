package me.d3sox.nevumi.ui

import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal actual fun rememberPlaylistFiles(): PlaylistFiles {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var opening by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    var saving by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { opening?.complete(it) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { saving?.complete(it) }
    DisposableEffect(Unit) { onDispose { opening?.cancel(); saving?.cancel() } }
    return remember(resolver, open, save) {
        object : PlaylistFiles {
            override suspend fun open(): PlaylistDocument? {
                val answer = CompletableDeferred<Uri?>()
                try {
                    withContext(Dispatchers.Main) {
                        check(opening == null && saving == null) { "A playlist picker is already open" }
                        opening = answer
                        open.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl",
                            "application/vnd.apple.mpegurl", "text/plain", "application/octet-stream"))
                    }
                    return answer.await()?.let { uri -> withContext(Dispatchers.IO) {
                        val name = requireNotNull(resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)).use {
                            check(it.moveToFirst()) { "The selected playlist is unavailable" }; it.getString(0)
                        }
                        val base = runCatching {
                            check(uri.authority == "com.android.externalstorage.documents")
                            val id = DocumentsContract.getDocumentId(uri)
                            val volume = context.getSystemService(StorageManager::class.java).storageVolumes.first {
                                if (id.substringBefore(':') == "primary") it.isPrimary else it.uuid == id.substringBefore(':')
                            }
                            File(requireNotNull(volume.directory), id.substringAfter(':')).parent.orEmpty()
                        }.getOrDefault("")
                        PlaylistDocument(name.substringBeforeLast('.', name),
                            requireNotNull(resolver.openInputStream(uri)) { "The selected playlist is unavailable" }.use(::readPlaylist), base)
                    } }
                } finally { withContext(NonCancellable + Dispatchers.Main) { if (opening === answer) opening = null } }
            }

            override suspend fun save(name: String, text: String): Boolean {
                val answer = CompletableDeferred<Uri?>()
                try {
                    withContext(Dispatchers.Main) {
                        check(opening == null && saving == null) { "A playlist picker is already open" }
                        saving = answer
                        save.launch(name)
                    }
                    val uri = answer.await() ?: return false
                    withContext(Dispatchers.IO) {
                        requireNotNull(resolver.openOutputStream(uri, "wt")) { "The playlist could not be saved" }
                            .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    }
                    return true
                } finally { withContext(NonCancellable + Dispatchers.Main) { if (saving === answer) saving = null } }
            }
        }
    }
}
