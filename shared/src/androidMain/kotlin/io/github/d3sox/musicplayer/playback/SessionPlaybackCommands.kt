package io.github.d3sox.musicplayer.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import kotlinx.serialization.json.Json

/** Private app/session messages. They contain identifiers and controls, never audio locations. */
object SessionPlaybackCommands {
    const val STATUS = "me.d3sox.manida.PLAYBACK_STATUS"
    const val CROSSFADE = "me.d3sox.manida.CROSSFADE"
    const val REPEAT = "me.d3sox.manida.REPEAT"
    val actions = listOf(STATUS, CROSSFADE, REPEAT)
    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)
    fun encodeStatus(status: PlaybackStatus) = Bundle().apply { putString("status", Json.encodeToString(status)) }
    fun decodeStatus(bundle: Bundle): PlaybackStatus {
        val text = requireNotNull(bundle.getString("status"))
        require(text.length <= 65_536)
        return Json.decodeFromString<PlaybackStatus>(text)
    }
}
