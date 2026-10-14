package io.github.d3sox.musicplayer.queue

import kotlinx.serialization.Serializable

@Serializable
enum class QueueAction { PLAY_NEXT, PLAY_AFTER, APPEND }
