package me.d3sox.nevumi

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.sync.LibrarySync
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidLocalFileMatchingTest {
    @Test fun phoneContentUrisMatchTheSameDesktopFileWithoutExternalIds() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val id = UUID.randomUUID().toString()
        val file = File(app.cacheDir, "$id.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "nevumi-test-$id.flac")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/NevumiTests")
        }))
        val store = openMusicStore(app, "$id.db")
        try {
            app.contentResolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
            val metadata = TrackMetadata("Local file", durationMs = 3_000)
            val phone = app.controller.library.importTrack(metadata, TrackSource("phone:$id", SourceKind.LOCAL,
                uri.toString(), indexedSize = file.length()))
            app.controller.library.setFavorite(phone.id, true)
            val desktopLibrary = MusicLibrary(store)
            val desktop = desktopLibrary.importTrack(metadata, TrackSource("desktop:$id", SourceKind.LOCAL, file.path,
                indexedSize = file.length()))
            val snapshot = requireNotNull(app.controller.pairedDevices).library.snapshot()
            assertEquals(listOf(FileAudioAccess.contentId(file.path)), snapshot.tracks.single { it.key == phone.id }.contentIds)
            LibrarySync(store, desktopLibrary).merge(snapshot)
            assertTrue(requireNotNull(desktopLibrary.findTrack(desktop.id)).favorite)
        } finally {
            app.contentResolver.delete(uri, null, null)
            store.close(); file.delete()
            app.deleteDatabase("$id.db")
        }
    }
}
