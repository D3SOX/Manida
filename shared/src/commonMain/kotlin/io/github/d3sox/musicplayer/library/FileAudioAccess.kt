package io.github.d3sox.musicplayer.library

@kotlinx.serialization.Serializable
data class AudioContentId(val size: Long, val sha256: String)

expect object FileAudioAccess {
    fun available(location: String): Boolean
    fun contentId(location: String): AudioContentId?
}
