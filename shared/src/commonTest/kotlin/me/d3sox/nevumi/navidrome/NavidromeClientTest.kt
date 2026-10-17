package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class NavidromeClientTest {
    @Test
    fun theCatalogueIncludesEveryPageWhenTheServerCapsPageSize() = runBlocking {
        val engine = MockEngine { request ->
            val offset = request.url.parameters["songOffset"]?.toInt() ?: 0
            val songs = when (offset) {
                0 -> """[{"id":"a","title":"Northern Light"}]"""
                1 -> """[{"id":"b","title":"Long Way Home"}]"""
                2 -> """[{"id":"c","title":"Night Walk"}]"""
                else -> "[]"
            }
            respond("""{"subsonic-response":{"status":"ok","searchResult3":{"song":$songs}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine)
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test"))

            assertEquals(setOf("Northern Light", "Long Way Home", "Night Walk"),
                client.songs().map { it.metadata.title }.toSet())
        } finally { http.close() }
    }
}
