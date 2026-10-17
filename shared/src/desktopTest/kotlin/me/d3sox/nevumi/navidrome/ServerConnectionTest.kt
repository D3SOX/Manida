package me.d3sox.nevumi.navidrome

import me.d3sox.nevumi.security.SecretServiceVault
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ServerConnectionTest {
    @Test
    fun differentAccountsOnTheSameServerHaveSeparateSourceIdentities() {
        val http = HttpClient(OkHttp)
        try {
            val first = NavidromeClient(http, "https://music.example.test", ServerCredentials("first", "test"))
            val second = NavidromeClient(http, "https://music.example.test", ServerCredentials("second", "test"))
            kotlin.test.assertNotEquals(first.serverId, second.serverId)
            assertEquals(first.serverId, NavidromeClient(http, "https://music.example.test/", ServerCredentials("first", "changed-password")).serverId)
        } finally { http.close() }
    }

    @Test
    fun theServerCanBeUsedAfterRestartWithoutEnteringThePasswordAgain() = runBlocking {
        val directory = Files.createTempDirectory("server-connection-test")
        val path = directory.resolve("library.db").toString()
        val applicationId = "me.d3sox.nevumi.tests.${Uuid.random()}"
        val vault = SecretServiceVault(applicationId)
        val http = HttpClient(OkHttp)
        try {
            val store = openMusicStore(path)
            NavidromeConnection(store, http, vault).connect(
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            store.close()
            val restored = openMusicStore(path)
            try {
                val client = NavidromeConnection(restored, http, SecretServiceVault(applicationId)).client()
                assertEquals(setOf("Northern Light", "Long Way Home", "Night Walk"),
                    client?.songs()?.map { it.metadata.title }?.toSet())
            } finally { restored.close() }
        } finally { vault.delete("navidrome"); http.close(); directory.toFile().deleteRecursively() }
    }
}
