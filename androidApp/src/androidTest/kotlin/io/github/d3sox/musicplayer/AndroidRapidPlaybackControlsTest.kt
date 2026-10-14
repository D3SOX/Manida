package io.github.d3sox.musicplayer

import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.library.SourceKind
import io.github.d3sox.musicplayer.library.TrackMetadata
import io.github.d3sox.musicplayer.library.TrackSource
import io.github.d3sox.musicplayer.playback.AndroidPlaybackEngine
import io.github.d3sox.musicplayer.playback.RepeatMode
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidRapidPlaybackControlsTest {
    @Test
    fun albumShuffleAndRestoreKeepALargeAndroidQueuePlayable(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "album-shuffle-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val tracks = (0 until 500).map { index ->
            app.controller.library.importTrack(TrackMetadata("Album song $index", "Fixture artist",
                album = "Album ${index / 10}", albumArtist = "Fixture artist", trackNumber = index % 10 + 1),
                TrackSource("album-shuffle-$index", SourceKind.LOCAL, audio.path))
        }
        var reader: androidx.media3.session.MediaController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = app.controller
                controller.refresh()
                val order = (0 until 500).map { tracks[it * 71 % 500].id }
                controller.playTrack(order.first(), order).join()
                withTimeout(10_000) { controller.state.first { it.playback.playing } }
                controller.pause().join()
                controller.seek(1_000).join()
                withTimeout(5_000) { controller.state.first { !it.playback.playing && it.playback.positionMs in 950..1_100 } }
                lateinit var connection: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>
                instrumentation.runOnMainSync { connection = androidx.media3.session.MediaController.Builder(app,
                    androidx.media3.session.SessionToken(app, android.content.ComponentName(app, PlaybackService::class.java))).buildAsync() }
                val controls = connection.get(10, TimeUnit.SECONDS).also { reader = it }
                val original = controller.state.value.queue
                suspend fun awaitPlayerOrder(expected: List<String>) {
                    withTimeout(10_000) {
                        var actual = emptyList<String>()
                        while (actual != expected) {
                            instrumentation.runOnMainSync { actual = (0 until controls.mediaItemCount).map { controls.getMediaItemAt(it).mediaId } }
                            kotlinx.coroutines.delay(10)
                        }
                    }
                }
                val started = android.os.SystemClock.elapsedRealtime()
                controller.shuffleQueueAlbums().join()
                val shuffled = controller.state.value.queue
                awaitPlayerOrder(shuffled.entries.map { it.id })
                assertTrue("Album shuffle did not apply", shuffled.albumShuffled)
                assertEquals(original.entries.map { it.id }.toSet(), shuffled.entries.map { it.id }.toSet())
                assertEquals(original.currentEntryId, controller.state.value.playback.entryId)
                assertEquals(false, controller.state.value.playback.playing)
                assertTrue(controller.state.value.playback.positionMs in 950..1_100)
                assertTrue("Album shuffle took too long", android.os.SystemClock.elapsedRealtime() - started < 2_000)
                controller.setQueueShuffled(false).join()
                awaitPlayerOrder(original.entries.map { it.id })
                assertEquals(original.entries, controller.state.value.queue.entries)
                assertEquals(original.currentEntryId, controller.state.value.playback.entryId)
                controller.play().join()
                withTimeout(5_000) { controller.state.first { it.playback.playing && it.playback.positionMs > 1_100 } }
            }
        } finally {
            app.controller.pause().join()
            instrumentation.runOnMainSync { reader?.release() }
            audio.delete()
        }
    }

    @Test
    fun movingAnOccurrenceInALargeQueueUpdatesTheAndroidPlayerPromptly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "large-queue-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val track = app.controller.library.importTrack(TrackMetadata("Large queue"), TrackSource("large-queue", SourceKind.LOCAL, audio.path))
        var reader: androidx.media3.session.MediaController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                app.controller.refresh()
                app.controller.playTrack(track.id, List(500) { track.id }).join()
                withTimeout(10_000) { app.controller.state.first { it.playback.playing } }
                app.controller.pause().join()
                withTimeout(5_000) { app.controller.state.first { !it.playback.playing } }
                lateinit var connection: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>
                instrumentation.runOnMainSync { connection = androidx.media3.session.MediaController.Builder(app,
                    androidx.media3.session.SessionToken(app, android.content.ComponentName(app, PlaybackService::class.java))).buildAsync() }
                val controls = connection.get(10, TimeUnit.SECONDS).also { reader = it }
                val before = app.controller.state.value.queue
                val last = before.entries.last()
                val started = android.os.SystemClock.elapsedRealtime()
                app.controller.moveQueueEntry(last.id, 0).join()
                var first: String? = null
                withTimeout(10_000) {
                    while (first != last.id) {
                        instrumentation.runOnMainSync { first = controls.getMediaItemAt(0).mediaId }
                        kotlinx.coroutines.delay(10)
                    }
                }
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                assertTrue("Moving one queue occurrence blocked the player for ${elapsed}ms", elapsed < 1_500)
                assertEquals(before.currentEntryId, app.controller.state.value.playback.entryId)
                assertEquals(false, app.controller.state.value.playback.playing)
            }
        } finally {
            app.controller.pause().join()
            instrumentation.runOnMainSync { reader?.release() }
            audio.delete()
        }
    }

    @Test
    fun linkingThePausedSongKeepsItsOccurrenceAndPositionAndUpdatesItsLibraryIdentity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val name = "linked-playback-$id.db"
        val audio = File(context.cacheDir, "linked-playback-$id.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val store = openMusicStore(context, name)
        val library = MusicLibrary(store)
        val keep = library.importTrack(TrackMetadata("Kept song"), TrackSource("keep", SourceKind.LOCAL, audio.path))
        val other = library.importTrack(TrackMetadata("Other copy"), TrackSource("other", SourceKind.LOCAL, audio.path))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = MusicController(library, PlaybackQueue(store), AndroidPlaybackEngine(context, PlaybackService::class.java))
                try {
                    withTimeout(10_000) { controller.state.first { !it.loading } }
                    controller.playTrack(other.id, listOf(other.id, keep.id, other.id), contextIndex = 2).join()
                    withTimeout(10_000) { controller.state.first { it.playback.playing } }
                    controller.pause().join()
                    controller.seek(1_000).join()
                    withTimeout(5_000) { controller.state.first { !it.playback.playing && it.playback.positionMs in 990..1100 } }
                    val before = controller.state.value.queue
                    controller.linkTracks(keep.id, other.id).join()
                    val linked = withTimeout(5_000) { controller.state.first { it.playback.trackId == keep.id } }
                    assertEquals(before.currentEntryId, linked.playback.entryId)
                    assertEquals(before.entries.map { it.id }, linked.queue.entries.map { it.id })
                    assertEquals(List(3) { keep.id }, linked.queue.entries.map { it.trackId })
                    assertEquals(false, linked.playback.playing)
                    assertTrue(linked.playback.positionMs in 990..1100)
                    assertEquals(1, linked.tracks.size)
                } finally { controller.closeAndJoin() }
            }
        } finally { store.close(); context.deleteDatabase(name); audio.delete() }
    }

    @Test
    fun startingPlaybackDuringAMainThreadDelayKeepsTheRequestedSongRepeatCount() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "rapid-repeat-${UUID.randomUUID()}.db"
        val audio = File(context.cacheDir, "rapid-repeat-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val store = openMusicStore(context, name)
        val library = MusicLibrary(store)
        val track = library.importTrack(TrackMetadata("Rapid repeat"), TrackSource("repeat", SourceKind.LOCAL, audio.path))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = MusicController(library, PlaybackQueue(store), AndroidPlaybackEngine(context, PlaybackService::class.java))
                try {
                    withTimeout(10_000) { controller.state.first { !it.loading } }
                    controller.playTrack(track.id, listOf(track.id, track.id)).join()
                    controller.pause().join()
                    withTimeout(10_000) { controller.state.first { !it.playback.playing && it.playback.durationMs == 3_000L } }
                    Handler(Looper.getMainLooper()).post {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    assertTrue("Main-thread gate did not start", entered.await(3, TimeUnit.SECONDS))
                    controller.setQueueRepeat(RepeatMode.ONE, playCount = 3).join()
                    controller.play().join()
                    withTimeout(2_000) { controller.state.first { it.playback.playWhenReady } }
                    release.countDown()
                    val playing = withTimeout(5_000) { controller.state.first { it.playback.playing && it.playback.repeatPlays == 3 } }
                    assertEquals(RepeatMode.ONE to 3, playing.queue.repeatMode to playing.queue.repeatPlays)
                    Unit
                } finally { release.countDown(); controller.setQueueRepeat(RepeatMode.OFF).join(); controller.closeAndJoin() }
            }
        } finally { release.countDown(); store.close(); context.deleteDatabase(name); audio.delete() }
    }

    @Test
    fun twoNextCommandsDuringAMainThreadDelaySelectTheThirdSongAndKeepPlaying() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "rapid-next-${UUID.randomUUID()}.db"
        val audio = File(context.cacheDir, "rapid-next.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val store = openMusicStore(context, name)
        val library = MusicLibrary(store)
        val tracks = (1..3).map { index ->
            library.importTrack(TrackMetadata("Rapid next $index"), TrackSource("rapid-next-$index", SourceKind.LOCAL, audio.toURI().toString()))
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = MusicController(library, PlaybackQueue(store), AndroidPlaybackEngine(context, PlaybackService::class.java))
                try {
                    withTimeout(10_000) { controller.state.first { !it.loading } }
                    controller.playTrack(tracks[0].id, tracks.map { it.id }).join()
                    withTimeout(10_000) { controller.state.first { it.playback.playing && it.playback.positionMs in 100..300 } }
                    Handler(Looper.getMainLooper()).post {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    assertTrue("Main-thread gate did not start", entered.await(3, TimeUnit.SECONDS))
                    controller.skip(1).join()
                    controller.skip(1).join()
                    release.countDown()
                    val selected = withTimeout(5_000) { controller.state.first {
                        it.playback.trackId == tracks[2].id && it.playback.playing && it.playback.positionMs >= 100
                    } }
                    assertEquals(2, selected.queue.currentIndex)
                } finally { release.countDown(); controller.closeAndJoin() }
            }
        } finally { release.countDown(); store.close(); context.deleteDatabase(name); audio.delete() }
    }

    @Test
    fun rapidPauseAndResumeSurviveAMainThreadDelayWithoutLosingTheLatestCommand() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "rapid-controls-${UUID.randomUUID()}.db"
        val audio = File(context.cacheDir, "rapid-controls.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { output -> input.copyTo(output) } }
        val store = openMusicStore(context, name)
        val library = MusicLibrary(store)
        val track = library.importTrack(TrackMetadata("Rapid controls"), TrackSource("rapid-controls", SourceKind.LOCAL, audio.toURI().toString()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = MusicController(library, PlaybackQueue(store), AndroidPlaybackEngine(context, PlaybackService::class.java))
                try {
                    withTimeout(10_000) { controller.state.first { !it.loading } }
                    controller.playTrack(track.id, listOf(track.id)).join()
                    withTimeout(10_000) { controller.state.first { it.playback.playing && it.playback.positionMs in 100..300 } }
                    Handler(Looper.getMainLooper()).post {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    assertTrue("Main-thread gate did not start", entered.await(3, TimeUnit.SECONDS))
                    controller.togglePlayback().join()
                    controller.togglePlayback().join()
                    release.countDown()
                    val resumed = withTimeout(5_000) { controller.state.first { it.playback.playing && it.playback.positionMs >= 500 } }
                    assertEquals(track.id, resumed.playback.trackId)
                } finally { release.countDown(); controller.closeAndJoin() }
            }
        } finally { release.countDown(); store.close(); context.deleteDatabase(name); audio.delete() }
    }
}
