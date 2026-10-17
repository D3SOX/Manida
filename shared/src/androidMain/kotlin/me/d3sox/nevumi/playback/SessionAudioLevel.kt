package me.d3sox.nevumi.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The UI and media service live in the same app process. Keep frame-rate data off Binder and AppState. */
object SessionAudioLevel {
    val requested = MutableStateFlow(false)
    private val current = MutableStateFlow(0f)
    val level = current.asStateFlow()
    fun publish(value: Float) { current.value = value }
}
