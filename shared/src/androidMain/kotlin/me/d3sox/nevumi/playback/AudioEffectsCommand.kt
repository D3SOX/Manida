package me.d3sox.nevumi.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import kotlinx.serialization.json.Json

object AudioEffectsCommand {
    const val ACTION = "me.d3sox.nevumi.AUDIO_EFFECTS"
    private const val DATA = "effects"
    val command: SessionCommand get() = SessionCommand(ACTION, Bundle.EMPTY)

    fun encode(effects: AudioEffects): Bundle {
        effects.validate()
        return Bundle().apply { putString(DATA, Json.encodeToString(effects)) }
    }

    fun decode(arguments: Bundle): AudioEffects {
        val text = requireNotNull(arguments.getString(DATA))
        require(text.length <= 65_536)
        return Json.decodeFromString<AudioEffects>(text).also { it.validate() }
    }
}
