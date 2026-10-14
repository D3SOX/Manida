package io.github.d3sox.musicplayer

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.library.AndroidLocalSources
import io.github.d3sox.musicplayer.library.SourceResolver
import io.github.d3sox.musicplayer.playlists.M3uPlaylists
import io.github.d3sox.musicplayer.playlists.Playlists
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidM3uPlaylistsTest {
    @Test
    fun anM3uFilePathResolvesToTheIndexedDeviceSongAndKeepsItsPlaybackUri() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val resolver = app.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Manida-m3u-${UUID.randomUUID()}")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        try {
            instrumentation.context.assets.open("fixture.flac").use { input -> requireNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val path = requireNotNull(resolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)).use {
                check(it.moveToFirst()); it.getString(0)
            }
            val library = app.controller.library
            val song = AndroidLocalSources(app, library).scan(uri.toString()).single()
            val files = M3uPlaylists(library, Playlists(app.store))
            val imported = files.import("Device songs", "#EXTM3U\n$path\n")
            assertEquals(listOf(song.id), imported.playlist.trackIds)
            assertTrue(path in files.export(imported.playlist.id))
            assertEquals(uri.toString(), SourceResolver.resolve(library.findTrack(song.id)!!.sources)!!.location)
        } finally { resolver.delete(uri, null, null) }
    }
}
