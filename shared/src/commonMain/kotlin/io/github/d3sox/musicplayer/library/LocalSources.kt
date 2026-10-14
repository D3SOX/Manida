package io.github.d3sox.musicplayer.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface LocalSources {
    suspend fun scan(location: String, force: Boolean = false, onProgress: suspend (LocalScanProgress) -> Unit = {}): List<Track>
    suspend fun roots(): List<String> = emptyList()
    suspend fun pendingRefreshLocations(): List<String> = emptyList()
    val changes: Flow<Unit> get() = emptyFlow()
}

data class LocalScanProgress(val processed: Int, val total: Int)
