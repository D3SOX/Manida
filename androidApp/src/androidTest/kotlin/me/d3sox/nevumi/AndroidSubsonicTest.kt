package me.d3sox.nevumi

import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.navidrome.*
import me.d3sox.nevumi.security.AndroidCredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AndroidSubsonicTest {
    @Test fun gonicLibrariesAndPlaylistsWorkWithCredentialsRestoredFromTheAndroidVault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val store = openMusicStore(context, "subsonic-$id.db")
        val vault = AndroidCredentialVault(context, "subsonic-test.$id")
        val http = HttpClient(OkHttp)
        try {
            NavidromeConnection(store, http, vault).connect("http://127.0.0.1:14537", ServerCredentials("admin", "admin"))
            val restored = NavidromeConnection(store, http, AndroidCredentialVault(context, "subsonic-test.$id"))
            val client = requireNotNull(restored.client())
            val songs = client.songs()
            assertEquals(setOf("Northern Light", "Night Walk", "Long Way Home"), songs.map { it.metadata.title }.toSet())
            val playlist = client.savePlaylist("Android fixture $id", listOf(songs.first().id, songs.first().id))
            try {
                assertEquals(2, playlist.songIds.size)
                assertEquals("Updated", client.savePlaylist("Updated", listOf(songs.last().id), playlist.id).name)
            } finally { client.deletePlaylist(playlist.id) }
            restored.disconnect()
            assertNull(restored.client())
        } finally { http.close(); store.close(); context.deleteDatabase("subsonic-$id.db") }
    }
}
