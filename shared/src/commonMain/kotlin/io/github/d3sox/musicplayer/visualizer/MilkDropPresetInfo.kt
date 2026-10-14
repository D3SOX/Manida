package io.github.d3sox.musicplayer.visualizer

import kotlinx.serialization.Serializable

@Serializable
data class MilkDropPresetInfo(val name: String, val collection: String, val category: String = "")
