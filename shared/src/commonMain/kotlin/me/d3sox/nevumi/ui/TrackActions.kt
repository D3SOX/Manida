package me.d3sox.nevumi.ui

import me.d3sox.nevumi.settings.TrackAction
import me.d3sox.nevumi.library.LibraryTab
import androidx.compose.runtime.*

internal fun TrackAction.label(favorite: Boolean = false, availableOffline: Boolean = false) = when (this) {
    TrackAction.NONE -> "None"
    TrackAction.PLAY_NEXT -> "Play next"
    TrackAction.PLAY_AFTER -> "Play after"
    TrackAction.APPEND -> "Add to queue"
    TrackAction.PLAY -> "Play"
    TrackAction.FAVORITE -> if (favorite) "Remove favorite" else "Favorite"
    TrackAction.RATE -> "Rate song"
    TrackAction.ADD_TO_PLAYLIST -> "Add to playlist"
    TrackAction.DOWNLOAD -> if (availableOffline) "Available offline" else "Download"
    TrackAction.GO_ARTIST -> "Go to artist"
    TrackAction.GO_ALBUM -> "Go to album"
    TrackAction.GO_ALBUM_ARTIST -> "Go to album artist"
    TrackAction.GO_GENRE -> "Go to genre"
    TrackAction.GO_FOLDER -> "Go to folder"
    TrackAction.SELECT -> "Select song"
    TrackAction.SHOW_SOURCES -> "Song sources"
}

internal fun TrackAction.icon(favorite: Boolean = false, rating: Int = 0, inPlaylist: Boolean = false, availableOffline: Boolean = false) = when (this) {
    TrackAction.NONE -> AppIcon.CLOSE
    TrackAction.PLAY_NEXT -> AppIcon.PLAY_NEXT
    TrackAction.PLAY_AFTER -> AppIcon.PLAY_AFTER
    TrackAction.APPEND -> AppIcon.APPEND
    TrackAction.PLAY -> AppIcon.PLAY
    TrackAction.FAVORITE -> if (favorite) AppIcon.FAVORITE_FILLED else AppIcon.FAVORITE
    TrackAction.RATE -> if (rating > 0) AppIcon.RATING_FILLED else AppIcon.RATING
    TrackAction.ADD_TO_PLAYLIST -> if (inPlaylist) AppIcon.PLAYLIST_CHECK else AppIcon.PLAYLIST_PLUS
    TrackAction.DOWNLOAD -> if (availableOffline) AppIcon.AVAILABLE_OFFLINE else AppIcon.DOWNLOAD
    TrackAction.GO_ARTIST -> AppIcon.ARTIST
    TrackAction.GO_ALBUM -> AppIcon.ALBUM
    TrackAction.GO_ALBUM_ARTIST -> AppIcon.ALBUM_ARTIST
    TrackAction.GO_GENRE -> AppIcon.GENRE
    TrackAction.GO_FOLDER -> AppIcon.FOLDER
    TrackAction.SELECT -> AppIcon.CHECK
    TrackAction.SHOW_SOURCES -> AppIcon.LIBRARY
}

/** Tracks whether the current library page shows songs or a collection browser. */
internal class SongGesturePages { val activeLists = mutableStateMapOf<LibraryTab, Int>() }
internal val LocalSongGesturePages = compositionLocalOf<SongGesturePages?> { null }
