package me.d3sox.nevumi

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class MediaWriteRequest(val id: String, val sender: IntentSender, internal val answer: CompletableDeferred<Boolean>)

class MediaWriteRequests(private val context: Context) {
    private val requests = Mutex()
    private val mutablePending = MutableStateFlow<MediaWriteRequest?>(null)
    val pending = mutablePending.asStateFlow()

    suspend fun request(uri: Uri): Boolean = requests.withLock {
        val request = MediaWriteRequest(UUID.randomUUID().toString(),
            MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender, CompletableDeferred())
        mutablePending.value = request
        try { request.answer.await() } finally { mutablePending.value = null }
    }

    fun complete(granted: Boolean) { pending.value?.answer?.complete(granted) }
}
