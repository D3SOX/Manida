package me.d3sox.nevumi.settings

import kotlinx.serialization.Serializable

@Serializable
enum class TrackAction {
    NONE, PLAY_NEXT, PLAY_AFTER, APPEND, PLAY, FAVORITE, RATE, ADD_TO_PLAYLIST, DOWNLOAD,
    GO_ARTIST, GO_ALBUM, GO_ALBUM_ARTIST, GO_GENRE, GO_FOLDER, SELECT, SHOW_SOURCES
}
