package me.d3sox.nevumi

import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.library.Track

internal fun AppState.songAt(location: String): Track? =
    tracks.singleOrNull { track -> track.sources.any { it.location == location } }
