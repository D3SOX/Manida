package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidPairingUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun theAndroidAppPairsAndSyncsWithARealLinuxDesktop() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val devices = requireNotNull(app.controller.pairedDevices) { "Paired device sync is unavailable in the Android app" }
        val controlPort = File("/data/local/tmp/nevumi-sync-control-port").readText().trim().toInt()
        val invitation = java.net.URI("http://127.0.0.1:$controlPort/invitation").toURL().openConnection().apply {
            connectTimeout = 5_000; readTimeout = 5_000
        }.getInputStream().bufferedReader().use { it.readText() }
        val track = runBlocking { app.controller.library.importTrack(TrackMetadata("Night walk", durationMs = 120_000),
            TrackSource("server:fixture:night", SourceKind.SERVER, "night")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { !app.controller.state.value.loading }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Paired devices").performScrollTo().performClick()
            compose.onNodeWithText("Enter code").performScrollTo().performClick()
            compose.onNodeWithText("Pairing invitation").performTextInput(invitation)
            compose.onNodeWithText("Pair device").performClick()
            compose.waitUntil(10_000) { devices.state.value.peers.size == 1 && !devices.state.value.busy }
            compose.onNodeWithText("Linux desktop").assertExists()
            compose.onNodeWithContentDescription("Sync with Linux desktop").performScrollTo().performClick()
            compose.waitUntil(10_000) { app.controller.state.value.tracks.first { song -> song.id == track.id }.favorite && !devices.state.value.busy }
            assertNull(app.controller.state.value.error)
            compose.onNodeWithContentDescription("Remove Linux desktop").performClick()
            compose.onNodeWithText("Remove device").performClick()
            compose.waitUntil(5_000) { devices.state.value.peers.isEmpty() }
        }
    }
}
