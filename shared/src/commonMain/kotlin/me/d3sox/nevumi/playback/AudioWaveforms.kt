package me.d3sox.nevumi.playback

import me.d3sox.nevumi.library.TrackSource

interface AudioWaveforms {
    suspend fun load(source: TrackSource, durationMs: Long): List<Float>
    /** Read a prepared result without starting a decoder. */
    suspend fun cached(source: TrackSource): List<Float>? = null
}
