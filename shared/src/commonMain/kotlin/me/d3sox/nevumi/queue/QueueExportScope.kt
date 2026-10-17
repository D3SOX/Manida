package me.d3sox.nevumi.queue

enum class QueueExportScope(val label: String) {
    ALL("Everything"), REMAINING("Current song and remaining"), UP_NEXT("Up next");

    fun trackIds(snapshot: QueueSnapshot): List<String> = when (this) {
        ALL -> snapshot.entries
        REMAINING -> snapshot.entries.drop(snapshot.currentIndex.coerceAtLeast(0))
        UP_NEXT -> snapshot.upNext
    }.map { it.trackId }
}
