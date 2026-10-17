package me.d3sox.nevumi.queue

import kotlinx.serialization.Serializable

@Serializable
enum class QueueAction { PLAY_NEXT, PLAY_AFTER, APPEND }
