package me.d3sox.nevumi.queue

import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.QueueRecoveryMode
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class QueueSnapshotsTest {
    @Test fun fixedSnapshotsRestoreHistoryDuplicateOccurrencesSectionsAndPositionWithoutSavingLaterEdits() = runBlocking {
        val directory = Files.createTempDirectory("fixed-queue-snapshot")
        val path = directory.resolve("library.db").toString()
        var store = openMusicStore(path)
        try {
            var queue = PlaybackQueue(store)
            queue.replace(listOf("past", "same", "same", "last"), startIndex = 2, contextName = "Album")
            queue.append("request")
            queue.setPosition(1_450)
            val snapshot = queue.save("Album checkpoint", kind = SavedQueueKind.SNAPSHOT)
            val duplicate = queue.duplicateSaved(snapshot.id, "Copy")
            assertEquals(snapshot.snapshot, duplicate.snapshot)
            queue.restoreSaved(snapshot.id)
            assertNull(queue.snapshot().selectedSavedQueueId)
            assertEquals(snapshot.snapshot, queue.snapshot())
            queue.remove(queue.snapshot().upNext.single().id)
            queue.setPosition(2_000)
            assertEquals(snapshot, queue.saved().first { it.id == snapshot.id })
            store.close(); store = openMusicStore(path); queue = PlaybackQueue(store)
            queue.restoreSaved(snapshot.id)
            assertEquals(snapshot.snapshot, queue.snapshot())
            queue.convertSaved(snapshot.id, SavedQueueKind.LIVE)
            queue.restoreSaved(snapshot.id)
            queue.append("new")
            assertEquals(queue.snapshot().copy(selectedSavedQueueId = null), queue.saved().first { it.id == snapshot.id }.snapshot)
            queue.convertSaved(snapshot.id, SavedQueueKind.SNAPSHOT)
            assertNull(queue.snapshot().selectedSavedQueueId)
            val converted = queue.saved().first { it.id == snapshot.id }
            queue.append("another")
            assertEquals(converted, queue.saved().first { it.id == snapshot.id })
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun recoveryIsOptionalCapturesBeforeDestructiveActionsAndKeepsFifteenUniqueContentsWithoutRemovingManualSaves() = runBlocking {
        val directory = Files.createTempDirectory("queue-recovery")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val preferences = MusicPreferences(store)
            val queue = PlaybackQueue(store)
            queue.replace(listOf("past", "playing", "playing", "next"), 2)
            queue.setPosition(1_234)
            val original = queue.snapshot()
            val manual = queue.save("Manual", kind = SavedQueueKind.SNAPSHOT)
            queue.replace(listOf("other"))
            assertEquals(listOf(manual), queue.saved())
            preferences.update { it.copy(queueRecoveryEnabled = true) }
            queue.restore(original)
            queue.clear(QueueClear.ALL)
            assertEquals(original, queue.saved().first { it.kind == SavedQueueKind.RECOVERY && it.snapshot.entries.size == 4 }.snapshot)
            queue.restore(original)
            queue.setPosition(1_800)
            queue.replace(listOf("other"))
            val recoveries = queue.saved().filter { it.kind == SavedQueueKind.RECOVERY }
            assertEquals(1, recoveries.count { it.snapshot.entries.size == 4 })
            assertEquals(1_800L, recoveries.first { it.snapshot.entries.size == 4 }.snapshot.positionMs)
            preferences.update { it.copy(queueRecoveryEnabled = false) }
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds() + 1_000
            for (index in 0..19) {
                queue.replace(listOf("unique:$index")); queue.captureRecovery(now = now + index)
            }
            assertEquals(15, queue.saved().count { it.kind == SavedQueueKind.RECOVERY })
            assertEquals((5..19).map { "unique:$it" }.toSet(), queue.saved().filter { it.kind == SavedQueueKind.RECOVERY }
                .map { it.snapshot.currentTrackId }.toSet())
            assertEquals(manual, queue.saved().first { it.id == manual.id })
            preferences.update { it.copy(queueRecoveryEnabled = false) }
            val before = queue.saved()
            queue.replace(listOf("disabled")); queue.clear(QueueClear.ALL)
            assertEquals(before, queue.saved())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun editCaptureStoresTheEditedQueueAndRepeatedTimerCheckpointsDoNotFillTheHistory() = runBlocking {
        val directory = Files.createTempDirectory("queue-edit-recovery")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val preferences = MusicPreferences(store)
            preferences.update { it.copy(queueRecoveryEnabled = true, queueRecoveryMode = QueueRecoveryMode.ON_CHANGE) }
            val queue = PlaybackQueue(store)
            queue.replace(listOf("first", "last"))
            queue.append("request")
            assertTrue(queue.saved().any { it.snapshot == queue.snapshot() })
            preferences.update { it.copy(queueRecoveryMode = QueueRecoveryMode.TIMER) }
            repeat(20) { queue.setPosition(it * 100L); queue.captureRecovery() }
            assertEquals(2, queue.saved().count { it.kind == SavedQueueKind.RECOVERY })
            assertEquals(1_900L, queue.saved().first { it.snapshot.entries.size == 3 }.snapshot.positionMs)
            queue.replace(listOf("first", "last")) // New entry IDs for identical queue contents.
            queue.captureRecovery()
            assertEquals(2, queue.saved().count { it.kind == SavedQueueKind.RECOVERY })
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
