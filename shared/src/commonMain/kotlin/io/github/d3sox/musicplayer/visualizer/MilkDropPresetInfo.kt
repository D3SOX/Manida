package io.github.d3sox.musicplayer.visualizer

import kotlinx.serialization.Serializable

@Serializable
data class MilkDropPresetInfo(val name: String, val collection: String, val category: String = "",
    val id: String = "$collection/$category/$name")

@Serializable
data class MilkDropSettings(val selectedPresetId: String? = null, val automatic: Boolean = true,
    val favorites: Set<String> = emptySet(), val favoritesOnly: Boolean = false,
    val collection: String? = null, val category: String? = null,
    /** Shows the visualizer behind the now playing screen, dimmed toward the surface color and blurred by these fractions. */
    val background: Boolean = false, val backgroundDim: Float = .5f, val backgroundBlur: Float = 0f)

fun milkDropPlaybackIndices(presets: List<MilkDropPresetInfo>, settings: MilkDropSettings): List<Int> =
    presets.indices.filter { !settings.favoritesOnly || presets[it].id in settings.favorites }

fun milkDropNextPreset(indices: List<Int>, selected: Int, direction: Int = 1): Int? {
    if (indices.isEmpty()) return null
    val current = indices.indexOf(selected)
    if (current < 0) return if (direction < 0) indices.last() else indices.first()
    return indices[((current + direction) % indices.size + indices.size) % indices.size]
}

/** Picks another eligible preset at random, or the only one when there's no other. */
fun milkDropRandomPreset(indices: List<Int>, selected: Int, random: kotlin.random.Random = kotlin.random.Random): Int? =
    indices.filter { it != selected }.randomOrNull(random) ?: indices.firstOrNull()
