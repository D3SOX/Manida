package me.d3sox.manida

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import java.util.UUID

data class LocalNetworkRequest(val id: String, internal val answer: CompletableDeferred<Boolean>)

class LocalNetworkRequests(private val context: Context) {
    private val requests = Mutex()
    private val mutablePending = MutableStateFlow<LocalNetworkRequest?>(null)
    val pending = mutablePending.asStateFlow()

    suspend fun request(address: String) {
        if (Build.VERSION.SDK_INT < 37 || granted()) return
        val host = if (address.contains("://")) URI(address).host.orEmpty() else address
        val local = host.isEmpty() || host.endsWith(".local", ignoreCase = true) || withContext(Dispatchers.IO) {
            runCatching { InetAddress.getAllByName(host).any { ip ->
                val bytes = ip.address
                !ip.isLoopbackAddress && (ip.isSiteLocalAddress || ip.isLinkLocalAddress ||
                    (bytes.size == 4 && bytes[0].toInt() and 255 == 100 && bytes[1].toInt() and 255 in 64..127) ||
                    (bytes.size == 16 && bytes[0].toInt() and 254 == 252))
            } }.getOrDefault(false)
        }
        if (!local) return
        requests.withLock {
            if (granted()) return@withLock
            val request = LocalNetworkRequest(UUID.randomUUID().toString(), CompletableDeferred())
            mutablePending.value = request
            try {
                check(request.answer.await()) { "Allow nearby devices access to connect to your local music server." }
            } finally { mutablePending.value = null }
        }
    }

    @androidx.annotation.RequiresApi(37)
    private fun granted() = context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    fun complete(granted: Boolean) { pending.value?.answer?.complete(granted) }
}
