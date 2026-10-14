package me.d3sox.manida

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidLocalLibraryTest {
    @Test
    fun recentlyAddedUsesTheActualFileModificationTimeWhenTheMediaStoreDateIsStale() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val name = "modified-${UUID.randomUUID()}"
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Manida-$name")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        val store = openMusicStore(context, "$name.db")
        try {
            instrumentation.context.assets.open("fixture.flac").use { input ->
                requireNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val audio = requireNotNull(resolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)).use {
                check(it.moveToFirst()); java.io.File(it.getString(0))
            }
            val modifiedAt = 1_600_000_000_000L
            org.junit.Assert.assertTrue(audio.setLastModified(modifiedAt))
            val indexedDate = requireNotNull(resolver.query(uri, arrayOf(MediaStore.Audio.Media.DATE_MODIFIED), null, null, null)).use {
                check(it.moveToFirst()); it.getLong(0) * 1_000
            }
            org.junit.Assert.assertNotEquals(modifiedAt, indexedDate)
            val track = AndroidLocalSources(context, MusicLibrary(store)).scan(uri.toString()).single()
            assertEquals(modifiedAt, track.recentAt)
        } finally { store.close(); context.deleteDatabase("$name.db"); resolver.delete(uri, null, null) }
    }

    @Test
    fun deviceMusicImportsValidFilesAndReconcilesDeletedSongsWithoutLosingFavorites() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} $permission")).use { it.readBytes() }
        val id = UUID.randomUUID().toString()
        val resolver = context.contentResolver
        val files = listOf("corrupt.flac", "fixture.flac", "fixture.mp3", "fixture.wav").map { asset ->
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, asset)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MusicPlayer-$id")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
            requireNotNull(resolver.openOutputStream(uri)).use { output ->
                if (asset == "corrupt.flac") output.write("This is not an audio file".toByteArray())
                else instrumentation.context.assets.open(asset).use { input -> input.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            uri
        }
        val name = "local-$id.db"
        val store = openMusicStore(context, name)
        var removedFromDevice = false
        try {
            val library = MusicLibrary(store)
            AndroidLocalSources(context, library).scan("device")
            assertEquals(listOf("device"), AndroidLocalSources(context, library).roots())

            assertEquals(mapOf("Northern Light" to "Fixture Album", "Night Walk" to "Fixture Album",
                "Long Way Home" to "Fixture Album"), library.tracks().filter { it.metadata.artist == "Fixture Artist" }
                .associate { it.metadata.title to it.metadata.album })
            val removed = library.tracks().single { track -> track.sources.any { it.location == files[1].toString() } }
            val modifiedAt = requireNotNull(resolver.query(files[1], arrayOf(MediaStore.Audio.Media.DATE_MODIFIED), null, null, null)).use {
                check(it.moveToFirst()); it.getLong(0) * 1_000
            }
            org.junit.Assert.assertTrue(modifiedAt > 0)
            assertEquals(modifiedAt / 1_000, removed.recentAt / 1_000)
            library.setFavorite(removed.id, true)
            val preferences = io.github.d3sox.musicplayer.settings.MusicPreferences(store)
            preferences.update { it.copy(excludeFolders = listOf("Music/MusicPlayer-$id")) }
            AndroidLocalSources(context, library).scan("device")
            assertEquals(false, library.findTrack(removed.id)!!.sources.single().available)
            assertEquals(true, library.findTrack(removed.id)!!.favorite)
            preferences.update { it.copy(includeFolders = listOf("Music/MusicPlayer-$id"), excludeFolders = emptyList()) }
            AndroidLocalSources(context, library).scan("device")
            assertEquals(true, library.findTrack(removed.id)!!.sources.single().available)
            resolver.delete(files[1], null, null)
            removedFromDevice = true
            AndroidLocalSources(context, library).scan("device")
            val missing = library.findTrack(removed.id)!!
            assertEquals(null, SourceResolver.resolve(missing.sources, online = false))
            assertEquals(true, missing.favorite)
        } finally {
            store.close(); context.deleteDatabase(name)
            files.filterIndexed { index, _ -> index != 1 || !removedFromDevice }.forEach { resolver.delete(it, null, null) }
        }
    }
    @Test
    fun cachedDeviceIndexNeedsNoRescanUntilMediaOrFolderRulesChange() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} $permission")).use { it.readBytes() }
        instrumentation.awaitMediaStoreIdle()
        val name = "checkpoint-${UUID.randomUUID()}.db"
        var store = openMusicStore(context, name)
        var uri: android.net.Uri? = null
        try {
            AndroidLocalSources(context, MusicLibrary(store)).scan("device")
            store.close(); store = openMusicStore(context, name)
            var scanner = AndroidLocalSources(context, MusicLibrary(store))
            assertEquals(emptyList<String>(), scanner.pendingRefreshLocations())
            uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "checkpoint.flac")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Manida-checkpoint")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }))
            instrumentation.context.assets.open("fixture.flac").use { input ->
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { input.copyTo(it) }
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            assertEquals(listOf("device"), scanner.pendingRefreshLocations())
            instrumentation.awaitMediaStoreIdle()
            scanner.scan("device")
            assertEquals(emptyList<String>(), scanner.pendingRefreshLocations())
            io.github.d3sox.musicplayer.settings.MusicPreferences(store).update { it.copy(excludeFolders = listOf("Music/Manida-checkpoint")) }
            assertEquals(listOf("device"), scanner.pendingRefreshLocations())
            scanner.scan("device")
            assertEquals(emptyList<String>(), scanner.pendingRefreshLocations())
        } finally {
            store.close(); context.deleteDatabase(name)
            uri?.let { context.contentResolver.delete(it, null, null) }
        }
    }

}
