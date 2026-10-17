package me.d3sox.nevumi.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

class LinuxDownloadNetwork(address: String? = null) : AutoCloseable {
    private val mutableState = MutableStateFlow(DownloadNetwork(connected = false, metered = true))
    val state = mutableState.asStateFlow()
    private val connection = (address?.let(DBusConnectionBuilder::forAddress)
        ?: DBusConnectionBuilder.forSystemBus()).withShared(false).build()
    private val values = mutableMapOf<String, Variant<*>>()

    init {
        try {
            val properties = connection.getRemoteObject("org.freedesktop.NetworkManager", "/org/freedesktop/NetworkManager", Properties::class.java)
            val owner = connection.getDBusOwnerName("org.freedesktop.NetworkManager")
            connection.addSigHandler(Properties.PropertiesChanged::class.java, owner, properties) { signal ->
                if (signal.interfaceName == "org.freedesktop.NetworkManager") synchronized(values) {
                    values.putAll(signal.propertiesChanged)
                    signal.propertiesRemoved.forEach(values::remove)
                    publish()
                }
            }
            synchronized(values) {
                values.putAll(properties.GetAll("org.freedesktop.NetworkManager"))
                publish()
            }
        } catch (error: Exception) { connection.close(); throw error }
    }

    private fun publish() {
        val connectivity = (values["State"]?.value as? Number)?.toInt() ?: 0
        val metered = (values["Metered"]?.value as? Number)?.toInt() ?: 0
        mutableState.value = DownloadNetwork(connected = connectivity == 0 || connectivity >= 50,
            metered = metered == 1 || metered == 3)
    }

    override fun close() { connection.close() }
}
