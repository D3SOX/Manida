package io.github.d3sox.musicplayer.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberEqualizerFiles(): EqualizerFiles {
    val resolver = LocalContext.current.contentResolver
    var opening by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { opening?.complete(it) }
    DisposableEffect(Unit) { onDispose { opening?.cancel() } }
    return remember(resolver, picker) { EqualizerFiles {
        val answer = CompletableDeferred<Uri?>()
        try {
            withContext(Dispatchers.Main) {
                check(opening == null) { "An equalizer picker is already open" }
                opening = answer
                picker.launch(arrayOf("text/plain", "application/octet-stream"))
            }
            answer.await()?.let { uri -> withContext(Dispatchers.IO) {
                val name = requireNotNull(resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)).use {
                    check(it.moveToFirst()) { "The selected equalizer file is unavailable" }; it.getString(0)
                }.substringBeforeLast('.').removeSuffix(" ParametricEQ").removeSuffix(" FixedBandEQ")
                val text = requireNotNull(resolver.openInputStream(uri)) { "The selected equalizer file is unavailable" }.use(::readEqualizer)
                EqualizerDocument(name, text)
            } }
        } finally { withContext(NonCancellable + Dispatchers.Main) { if (opening === answer) opening = null } }
    } }
}
