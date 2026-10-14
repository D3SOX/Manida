package io.github.d3sox.musicplayer.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidDownloadNetwork(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutableState = MutableStateFlow(DownloadNetwork(connected = false, metered = true))
    val state = mutableState.asStateFlow()
    private var current: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            current = network
            mutableState.value = DownloadNetwork(connected = false, metered = true)
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (current == network) mutableState.value = DownloadNetwork(connected = true,
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }
        override fun onLost(network: Network) {
            if (current == network) {
                current = null
                mutableState.value = DownloadNetwork(connected = false, metered = true)
            }
        }
    }
    init { manager.registerDefaultNetworkCallback(callback) }
    override fun close() { manager.unregisterNetworkCallback(callback) }
}
