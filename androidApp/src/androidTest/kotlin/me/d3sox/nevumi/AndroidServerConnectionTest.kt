package me.d3sox.nevumi

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.d3sox.nevumi.navidrome.*
import me.d3sox.nevumi.security.AndroidCredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidServerConnectionTest {
    @Test
    fun theServerPasswordSurvivesRestartUsingAndroidKeystore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val name = "server-$id.db"
        val vault = AndroidCredentialVault(context, "test.$id")
        val http = HttpClient(OkHttp)
        val store = openMusicStore(context, name)
        try {
            NavidromeConnection(store, http, vault).connect("http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            store.close()
            val restored = openMusicStore(context, name)
            try {
                val client = NavidromeConnection(restored, http, AndroidCredentialVault(context, "test.$id")).client()
                assertEquals(setOf("Northern Light", "Long Way Home", "Night Walk"),
                    client?.songs()?.map { it.metadata.title }?.toSet())
            } finally { restored.close() }
        } finally { store.close(); vault.delete("navidrome"); http.close(); context.deleteDatabase(name) }
    }
}
