package me.d3sox.manida

import io.github.d3sox.musicplayer.app.AppState
import io.github.d3sox.musicplayer.library.Track

internal fun AppState.songAt(location: String): Track? =
    tracks.singleOrNull { track -> track.sources.any { it.location == location } }
