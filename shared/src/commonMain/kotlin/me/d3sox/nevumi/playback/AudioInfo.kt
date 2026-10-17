package me.d3sox.nevumi.playback

import kotlinx.serialization.Serializable

/** Encoded source information, independent of playback speed and output-device resampling. */
@Serializable
data class AudioInfo(val format: String, val bitrateBitsPerSecond: Long? = null, val sampleRateHz: Int? = null)

internal fun audioFormatName(codec: String?, location: String? = null): String? {
    val name = codec?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when (name) {
        "audio/mpeg", "mp3", "mp3float" -> "MP3"
        "audio/flac", "audio/x-flac", "flac" -> "FLAC"
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "wav" -> "WAV"
        "audio/mp4a-latm", "aac", "aac_latm" -> "AAC"
        "audio/opus", "opus" -> "Opus"
        "audio/vorbis", "vorbis" -> "Vorbis"
        "audio/alac", "alac" -> "ALAC"
        else -> if (name == "audio/raw" || name.startsWith("pcm_")) {
            if (location?.substringBefore('?')?.substringBefore('#')?.endsWith(".wav", ignoreCase = true) == true) "WAV" else "PCM"
        } else name.substringAfterLast('/').uppercase().take(12)
    }
}
