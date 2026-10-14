package io.github.d3sox.musicplayer.history

import io.github.d3sox.musicplayer.library.Track
import kotlin.time.Clock

enum class ListeningPeriod(val days: Int?) {
    ALL_TIME(null), LAST_7_DAYS(7), LAST_30_DAYS(30), LAST_365_DAYS(365)
}

data class ListeningRank(val name: String, val detail: String = "", val trackIds: List<String>,
    val playCount: Int, val listenedMs: Long)

data class ListeningReport(val playCount: Int, val listenedMs: Long, val songs: List<ListeningRank>,
    val artists: List<ListeningRank>, val albums: List<ListeningRank>, val genres: List<ListeningRank>)

fun listeningReport(listens: List<Listen>, tracks: List<Track>, period: ListeningPeriod = ListeningPeriod.ALL_TIME,
    now: Long = Clock.System.now().toEpochMilliseconds()): ListeningReport {
    val catalog = tracks.associateBy { it.id }
    val cutoff = period.days?.let { now - it * 86_400_000L } ?: Long.MIN_VALUE
    val events = listens.filter { it.trackId in catalog && it.startedAt in cutoff..now }
    fun ranks(keys: (Track) -> List<Pair<String, String>>): List<ListeningRank> {
        data class Group(val name: String, val detail: String, val ids: MutableSet<String> = linkedSetOf(),
            var plays: Int = 0, var milliseconds: Long = 0)
        val groups = linkedMapOf<Pair<String, String>, Group>()
        for (event in events) {
            val track = catalog.getValue(event.trackId)
            for ((name, detail) in keys(track).distinctBy { it.first.lowercase() to it.second.lowercase() }) {
                val group = groups.getOrPut(name.lowercase() to detail.lowercase()) { Group(name, detail) }
                group.ids += track.id
                if (event.qualified) group.plays++
                group.milliseconds += event.listenedMs
            }
        }
        return groups.values.map { ListeningRank(it.name, it.detail, it.ids.toList(), it.plays, it.milliseconds) }
            .sortedWith(compareByDescending<ListeningRank> { it.playCount }.thenByDescending { it.listenedMs }
                .thenBy { it.name.lowercase() }.thenBy { it.detail.lowercase() })
    }
    // Song identity is the canonical ID; titles can be identical for different recordings.
    val songs = events.groupBy { it.trackId }.map { (id, plays) ->
        val track = catalog.getValue(id)
        ListeningRank(track.metadata.title, track.metadata.artist, listOf(id), plays.count { it.qualified }, plays.sumOf { it.listenedMs })
    }.sortedWith(compareByDescending<ListeningRank> { it.playCount }.thenByDescending { it.listenedMs }
        .thenBy { it.name.lowercase() }.thenBy { it.trackIds.single() })
    return ListeningReport(events.count { it.qualified }, events.sumOf { it.listenedMs }, songs,
        ranks { listOf(it.metadata.artist.ifBlank { "Unknown artist" } to "") },
        ranks { listOf(it.metadata.album.ifBlank { "Unknown album" } to it.metadata.albumArtist.ifBlank { it.metadata.artist }) },
        ranks { it.metadata.genres.ifEmpty { listOf("Unknown genre") }.map { genre -> genre to "" } })
}
