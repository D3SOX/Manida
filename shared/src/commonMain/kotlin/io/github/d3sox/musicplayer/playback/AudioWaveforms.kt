package io.github.d3sox.musicplayer.playback

import io.github.d3sox.musicplayer.library.TrackSource

interface AudioWaveforms {
    suspend fun load(source: TrackSource, durationMs: Long): List<Float>
    /** Read a prepared result without starting a decoder. */
    suspend fun cached(source: TrackSource): List<Float>? = null
}
