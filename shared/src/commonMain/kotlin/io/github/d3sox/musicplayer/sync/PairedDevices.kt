package io.github.d3sox.musicplayer.sync

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import io.github.d3sox.musicplayer.queue.QueueSnapshot

@Serializable
data class PlaybackTransfer(val id: String, val queue: QueueSnapshot, val playWhenReady: Boolean,
    val speed: Double = 1.0, val pitch: Double = 1.0, val skippedSongs: Int = 0)

internal fun PlaybackTransfer.withAvailableEntries(entries: List<io.github.d3sox.musicplayer.queue.QueueEntry>): PlaybackTransfer {
    val ids = entries.map { it.id }.toSet()
    return copy(queue = queue.copy(entries = entries, lastInsertedEntryId = queue.lastInsertedEntryId?.takeIf { it in ids },
        unshuffledEntryIds = queue.unshuffledEntryIds?.filter { it in ids }), skippedSongs = skippedSongs + queue.entries.size - entries.size)
}

interface PlaybackReceiver {
    suspend fun receive(peerId: String, playback: PlaybackTransfer): Int
    suspend fun activate(peerId: String, transferId: String, positionMs: Long, playWhenReady: Boolean)
    suspend fun release(peerId: String, transferId: String): PlaybackTransfer
    suspend fun cancel(peerId: String, transferId: String)
}

@Serializable
data class SyncPeer(val deviceId: String, val name: String, val host: String, val port: Int, val certificateSha256: String,
    val lastSyncedAt: Long? = null)

data class PairedDevicesState(val peers: List<SyncPeer> = emptyList(), val invitation: String? = null,
    val invitationExpiresAt: Long? = null, val listeningPort: Int? = null, val busy: Boolean = false, val error: String? = null)

interface PairedDevices : AutoCloseable {
    val state: StateFlow<PairedDevicesState>
    val library: LibrarySync
    val localAddresses: List<String>
    suspend fun start()
    suspend fun createInvitation(host: String): String
    suspend fun cancelInvitation()
    suspend fun pair(invitation: String)
    suspend fun synchronize(deviceId: String)
    fun setPlaybackReceiver(receiver: PlaybackReceiver)
    suspend fun preparePlayback(deviceId: String, playback: PlaybackTransfer): Int
    suspend fun activatePlaybackTransfer(deviceId: String, transferId: String, positionMs: Long, playWhenReady: Boolean)
    suspend fun returnPlayback(deviceId: String, transferId: String): PlaybackTransfer
    suspend fun cancelPlaybackTransfer(deviceId: String, transferId: String)
    suspend fun forget(deviceId: String)
    suspend fun closeAndJoin()
}
