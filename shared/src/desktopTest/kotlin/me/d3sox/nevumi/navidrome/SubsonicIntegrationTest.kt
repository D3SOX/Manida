package me.d3sox.nevumi.navidrome

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid
import kotlin.test.*

class SubsonicIntegrationTest {
    @Test fun classicCatalogueDiscoveryAndPlaylistChangesWorkAgainstGonic() = runBlocking {
        val real = HttpClient(OkHttp)
        val visited = mutableListOf<String>()
        // Force the standard album route while all other requests reach the actual server.
        val http = HttpClient(MockEngine { request ->
            visited += request.url.encodedPath
            if (request.url.encodedPath.endsWith("/search3.view")) respond(
                """{"subsonic-response":{"status":"failed","error":{"code":10}}}""")
            else {
                val response = real.get(request.url)
                respond(response.bodyAsText(), response.status, response.headers)
            }
        })
        try {
            val client = NavidromeClient(http, "http://127.0.0.1:14537", ServerCredentials("admin", "admin"))
            val songs = client.songs()
            assertEquals(setOf("Northern Light", "Night Walk", "Long Way Home"), songs.map { it.metadata.title }.toSet())
            assertTrue(songs.all { it.metadata.genres == listOf("Electronic") && it.metadata.year == 2024 })
            val first = songs.first()
            client.setFavorite(first.id, true)
            assertTrue(client.songs().first { it.id == first.id }.favorite)
            client.setFavorite(first.id, false)
            client.setRating(first.id, 4)
            assertEquals(4, client.songs().first { it.id == first.id }.rating)
            client.setRating(first.id, 0)
            val playlist = client.savePlaylist("Nevumi fixture ${Uuid.random()}", listOf(first.id, first.id))
            try {
                assertEquals(listOf(first.id, first.id), playlist.songIds)
                val updated = client.savePlaylist("Renamed", listOf(songs.last().id, first.id), playlist.id, "From Nevumi")
                assertEquals("Renamed", updated.name)
                assertEquals("From Nevumi", updated.description)
                assertEquals(listOf(songs.last().id, first.id), updated.songIds)
            } finally { client.deletePlaylist(playlist.id) }
            assertTrue(visited.all { it.startsWith("/rest/") })
            val downloads = NavidromeClient(real, "http://127.0.0.1:14537", ServerCredentials("admin", "admin"))
            var bytes = 0
            downloads.downloadSong(first.id) { bytes += it.size }
            assertTrue(bytes > 100)
        } finally { http.close(); real.close() }
    }
}
