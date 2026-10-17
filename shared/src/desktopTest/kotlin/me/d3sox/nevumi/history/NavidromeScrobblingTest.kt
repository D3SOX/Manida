package me.d3sox.nevumi.history

import me.d3sox.nevumi.navidrome.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class NavidromeScrobblingTest {
    @Test
    fun reportingNowPlayingDoesNotIncrementTheRealServersPlayCountButASubmissionDoes() = runBlocking {
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val song = client.songs().first()
            suspend fun playCount(): Int = Json.parseToJsonElement(http.get(client.streamUrl(song.id)
                .replace("/stream.view", "/getSong.view")).bodyAsText()).jsonObject.getValue("subsonic-response")
                .jsonObject.getValue("song").jsonObject["playCount"]?.jsonPrimitive?.intOrNull ?: 0
            val before = playCount()
            val started = System.currentTimeMillis() - 5_000
            client.scrobble(song.id, started, submission = false)
            assertEquals(before, playCount())
            client.scrobble(song.id, started, submission = true)
            assertEquals(before + 1, playCount())
        } finally { http.close() }
    }

    @Test
    fun offlineSubmissionsPreserveTheOriginalMillisecondTimestamp() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/rest/scrobble.view", request.url.encodedPath)
            assertEquals("server-song", request.url.parameters["id"])
            assertEquals("1700000123456", request.url.parameters["time"])
            assertEquals("true", request.url.parameters["submission"])
            respond("""{"subsonic-response":{"status":"ok"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "fixture-password"))
            .scrobble("server-song", 1_700_000_123_456, submission = true) }
        finally { http.close() }
    }
}
