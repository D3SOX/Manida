package me.d3sox.nevumi.playback

import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
enum class EqualizerKind { PEAK, LOW_SHELF, HIGH_SHELF }

@Serializable
data class EqualizerBand(val frequencyHz: Double, val gainDb: Double = 0.0, val q: Double = 1.4,
    val kind: EqualizerKind = EqualizerKind.PEAK)

val graphicEqualizerFrequencies = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)

@Serializable
data class AudioEffects(
    val enabled: Boolean = false,
    val bands: List<EqualizerBand> = graphicEqualizerFrequencies.map { EqualizerBand(it) },
    val preampDb: Double = 0.0,
    val reduceGainForBoosts: Boolean = true,
    val mono: Boolean = false,
    val profileName: String? = null,
) {
    fun validate() {
        require(bands.size <= 32) { "Choose at most 32 equalizer bands" }
        require(preampDb in -30.0..12.0) { "Preamp must be between -30 and 12 dB" }
        require(bands.all { it.frequencyHz in 20.0..24_000.0 && it.gainDb in -24.0..24.0 && it.q in 0.1..10.0 }) {
            "Choose valid equalizer frequencies, gains and bandwidths"
        }
        require(profileName == null || profileName.length <= 200) { "Equalizer profile name is too long" }
    }

    val effectivePreampDb: Double get() = if (!enabled) 0.0 else preampDb -
        if (reduceGainForBoosts) max(0.0, bands.maxOfOrNull { it.gainDb } ?: 0.0) else 0.0
}
