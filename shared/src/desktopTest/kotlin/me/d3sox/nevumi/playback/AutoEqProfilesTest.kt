package me.d3sox.nevumi.playback

import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class AutoEqProfilesTest {
    @Test fun configuredProfilesRestoreAllEffectsAfterRestartWithoutNetworkRequests() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-custom-profile")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(MockEngine { error("Custom profiles must work offline") })
        try {
            val effects = AudioEffects(enabled = true, mono = true, preampDb = -4.0, reduceGainForBoosts = false,
                bands = listOf(EqualizerBand(120.0, 5.0, .7, EqualizerKind.LOW_SHELF)))
            val saved = AutoEqProfiles(store, http).save("  My profile  ", effects)
            store.close(); store = openMusicStore(database)
            val profiles = AutoEqProfiles(store, http)
            assertEquals(saved, profiles.saved().single())
            assertEquals(effects.copy(profileName = "My profile"), saved.effects)
            assertFailsWith<IllegalArgumentException> { profiles.save("", effects) }
            assertFailsWith<IllegalArgumentException> { profiles.save("Invalid", effects.copy(preampDb = 80.0)) }
            profiles.save("My profile", effects.copy(preampDb = -8.0))
            assertEquals(-8.0, profiles.saved().single().effects.preampDb)
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
    @Test
    fun profilesCanBeSearchedDownloadedAndSelectedAgainOfflineAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-autoeq")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            val text = if (request.url.encodedPath.endsWith("INDEX.md")) {
                """
                    # Index
                    - [Fixture Headphones (ANC On)](./Fixture/over-ear/Fixture%20Headphones%20(ANC%20On)) by Fixture measurements
                    - [Other Headphones](./Fixture/over-ear/Other%20Headphones) by Fixture measurements
                """.trimIndent()
            } else {
                assertTrue(request.url.encodedPath.endsWith("Fixture%20Headphones%20(ANC%20On)/Fixture%20Headphones%20(ANC%20On)%20ParametricEQ.txt"), request.url.toString())
                """
                    Preamp: -6.1 dB
                    Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
                    Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42
                    Filter 3: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70
                    Filter 4: OFF PK Fc 1000 Hz Gain 20 dB Q 1.0
                """.trimIndent()
            }
            respond(text, headers = headersOf(HttpHeaders.ContentType, "text/plain"))
        })
        try {
            var profiles = AutoEqProfiles(store, http)
            assertEquals(emptyList(), profiles.search("Fixture"))
            assertEquals(0, requests, "Opening the local catalog contacted a server")
            profiles.refreshCatalog()
            val result = profiles.search("fixture anc").single()
            val downloaded = profiles.download(result)
            assertEquals(3, downloaded.effects.bands.size)
            assertEquals(listOf(EqualizerKind.LOW_SHELF, EqualizerKind.PEAK, EqualizerKind.HIGH_SHELF), downloaded.effects.bands.map { it.kind })
            assertEquals(-6.1, downloaded.effects.preampDb)
            assertEquals(false, downloaded.effects.reduceGainForBoosts, "Imported preamp was applied twice")
            assertEquals(true, downloaded.effects.enabled)
            assertEquals("Fixture measurements", downloaded.attribution)
            assertEquals(2, requests)
            store.close()
            store = openMusicStore(database)
            profiles = AutoEqProfiles(store, http)
            assertEquals(result, profiles.search("fixture anc").single())
            assertEquals(downloaded, profiles.saved().single())
            assertEquals(downloaded, profiles.download(result))
            assertEquals(2, requests, "Saved profile was fetched again")
            Unit
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun unsupportedOrUnsafeImportsAreRejectedWithoutSavingAPartialProfile() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-autoeq-invalid")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(MockEngine { respond("not a catalog") })
        try {
            val profiles = AutoEqProfiles(store, http)
            for (text in listOf("Preamp: NaN dB\nFilter 1: ON PK Fc 1000 Hz Gain 1 dB Q 1", "Include: /private/music.txt",
                "Preamp: -6 dB\nFilter 1: ON PK Fc 1000 Hz Gain 1 dB Q 0", "Preamp: -6 dB\nFilter 1: ON HP Fc 1000 Hz")) {
                assertFailsWith<IllegalArgumentException> { profiles.import("Invalid", text) }
            }
            assertEquals(emptyList(), profiles.saved())
            assertFailsWith<IllegalArgumentException> { profiles.refreshCatalog() }
            assertEquals(emptyList(), profiles.search(""))
            Unit
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
