package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.sync.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.toPixelMap

@OptIn(ExperimentalTestApi::class)
class PairingUiTest {
    @Test fun invitationsAreShownAsReadableQrCodesAndScanningPairsThroughTheSameEncryptedHandshake() {
        val directory = Files.createTempDirectory("nevumi-qr-pairing-ui")
        val phoneStore = openMusicStore(directory.resolve("phone.db").toString())
        val desktopStore = openMusicStore(directory.resolve("desktop.db").toString())
        val phone = JvmPairedDevices(phoneStore, LibrarySync(phoneStore, MusicLibrary(phoneStore)), vault(), "Android phone")
        val desktop = JvmPairedDevices(desktopStore, LibrarySync(desktopStore, MusicLibrary(desktopStore)), vault(), "Linux desktop")
        val controller = MusicController(MusicLibrary(desktopStore), PlaybackQueue(desktopStore), MpvPlaybackEngine("null"), pairedDevices = desktop)
        try {
            val invitation = runBlocking { phone.createInvitation("127.0.0.1") }
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalPairingCamera provides { _, scanned, _ ->
                        TextButton(onClick = { scanned(invitation) }) { Text("Read fixture camera") }
                    }) { MusicPlayerApp(controller) }
                }
                waitUntil(timeoutMillis = 5_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Paired devices").performScrollTo().performClick()
                onNodeWithText("Create invitation").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { desktop.state.value.invitation != null }
                val pixels = onNodeWithContentDescription("Pairing QR code").performScrollTo().captureToImage().toPixelMap()
                val luminance = ByteArray(pixels.width * pixels.height) { i ->
                    val pixel = pixels[i % pixels.width, i / pixels.width]
                    (((pixel.red + 2 * pixel.green + pixel.blue) / 4) * 255).toInt().toByte()
                }
                assertEquals(desktop.state.value.invitation, decodePairingQr(luminance, pixels.width, pixels.height))
                onNodeWithText("Cancel invitation").performScrollTo().performClick()
                onNodeWithText("Scan QR code").performScrollTo().performClick()
                onNodeWithText("Use manual code").performClick()
                onNodeWithText("Enter pairing code").assertIsDisplayed()
                onNodeWithText("Pairing invitation").assertIsFocused()
                onNodeWithText("Cancel").performClick()
                onNodeWithText("Scan QR code").performScrollTo().performClick()
                onNodeWithText("Read fixture camera").performClick()
                waitUntil(timeoutMillis = 5_000) { desktop.state.value.peers.size == 1 && !desktop.state.value.busy }
                assertEquals("Android phone", desktop.state.value.peers.single().name)
                onNodeWithText("Scan pairing QR code").assertDoesNotExist()
            }
        } finally {
            runBlocking { controller.closeAndJoin(); phone.closeAndJoin(); desktop.closeAndJoin() }
            phoneStore.close(); desktopStore.close(); directory.toFile().deleteRecursively()
        }
    }
    @Test
    fun sharedSongsAndFolderRulesCanBeMatchedToThisDevicesLibrary() {
        val directory = Files.createTempDirectory("nevumi-sync-mapping-ui")
        val phoneStore = openMusicStore(directory.resolve("phone.db").toString())
        val desktopStore = openMusicStore(directory.resolve("desktop.db").toString())
        val phoneLibrary = MusicLibrary(phoneStore)
        val desktopLibrary = MusicLibrary(desktopStore)
        val desktop = JvmPairedDevices(desktopStore, LibrarySync(desktopStore, desktopLibrary), vault(), "Linux desktop")
        val controller = MusicController(desktopLibrary, PlaybackQueue(desktopStore), MpvPlaybackEngine("null"), pairedDevices = desktop)
        try {
            val phone = runBlocking { phoneLibrary.importTrack(TrackMetadata("Shared song", folder = "/phone/ambient"),
                TrackSource("local:phone", SourceKind.LOCAL, "/phone/song.mp3")) }
            val local = runBlocking { desktopLibrary.importTrack(TrackMetadata("My own song", folder = "/desktop/ambient"),
                TrackSource("local:desktop", SourceKind.LOCAL, "/desktop/song.mp3")) }
            val phonePlaylists = me.d3sox.nevumi.playlists.Playlists(phoneStore)
            val desktopPlaylists = me.d3sox.nevumi.playlists.Playlists(desktopStore)
            val smart = runBlocking { phonePlaylists.createSmart("Ambient folder", LibraryQuery(folder = "/phone/ambient")) }
            runBlocking {
                phoneLibrary.setFavorite(phone.id, true)
                desktop.library.merge(LibrarySync(phoneStore, phoneLibrary).snapshot())
            }
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Paired devices").performScrollTo().performClick()
                onNodeWithText("Songs to match").performScrollTo().performClick()
                onNodeWithText("Pair another device").assertDoesNotExist()
                captureUiSnapshot("songs-to-match")
                onNodeWithContentDescription("Match shared song: Shared song").performScrollTo().performClick()
                captureUiSnapshot("choose-song-copy")
                onNode(hasText("My own song") and hasText("/desktop/ambient")).performClick()
                waitUntil(timeoutMillis = 5_000) { runBlocking { desktop.library.unmatched().isEmpty() } }
                assertEquals(true, runBlocking { desktopLibrary.findTrack(local.id) }!!.favorite)
                onNodeWithText("All songs matched").assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Choose folder for Ambient folder").performScrollTo().performClick()
                onNodeWithText("/desktop/ambient").performClick()
                waitUntil(timeoutMillis = 5_000) { runBlocking { desktop.library.pendingFolders().isEmpty() } }
                assertEquals("/desktop/ambient", runBlocking { desktopPlaylists.get(smart.id) }!!.rule!!.folder)
                assertEquals(listOf(local.id), runBlocking { desktopPlaylists.get(smart.id) }!!.trackIds)
            }
        } finally {
            runBlocking { controller.closeAndJoin(); desktop.closeAndJoin() }
            phoneStore.close(); desktopStore.close(); directory.toFile().deleteRecursively()
        }
    }

    private fun vault() = object : CredentialVault {
        private val secrets = ConcurrentHashMap<String, String>()
        override suspend fun save(key: String, secret: String) { secrets[key] = secret }
        override suspend fun read(key: String) = secrets[key]
        override suspend fun delete(key: String) { secrets.remove(key) }
    }

    @Test
    fun aDeviceCanBePairedSyncedAndRemovedFromSettings() {
        val directory = Files.createTempDirectory("nevumi-pairing-ui")
        val phoneStore = openMusicStore(directory.resolve("phone.db").toString())
        val desktopStore = openMusicStore(directory.resolve("desktop.db").toString())
        val phoneLibrary = MusicLibrary(phoneStore)
        val desktopLibrary = MusicLibrary(desktopStore)
        val phone = JvmPairedDevices(phoneStore, LibrarySync(phoneStore, phoneLibrary), vault(), "Android phone")
        val desktop = JvmPairedDevices(desktopStore, LibrarySync(desktopStore, desktopLibrary), vault(), "Linux desktop")
        val controller = MusicController(desktopLibrary, PlaybackQueue(desktopStore), MpvPlaybackEngine("null"), pairedDevices = desktop)
        try {
            val phoneTrack = runBlocking { phoneLibrary.importTrack(TrackMetadata("Night walk", durationMs = 120_000),
                TrackSource("server:fixture:night", SourceKind.SERVER, "night")) }
            val desktopTrack = runBlocking { desktopLibrary.importTrack(TrackMetadata("Night walk", durationMs = 120_000),
                TrackSource("server:fixture:night", SourceKind.SERVER, "night")) }
            runBlocking { phoneLibrary.setFavorite(phoneTrack.id, true) }
            val invitation = runBlocking { phone.createInvitation("127.0.0.1") }
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Paired devices").performScrollTo().performClick()
                onNodeWithText("Enter code").performScrollTo().performClick()
                onNodeWithText("Scan pairing QR code").assertDoesNotExist()
                onNodeWithText("Pairing invitation").assertIsFocused().performTextInput("invalid code")
                onNodeWithText("Pair device").performClick()
                waitUntil(timeoutMillis = 5_000) { desktop.state.value.error != null && !desktop.state.value.busy }
                onNodeWithText("Enter pairing code").assertIsDisplayed()
                onNodeWithText("Pairing invitation").assertTextContains("invalid code").performTextReplacement(invitation)
                onNodeWithText("Pair device").performClick()
                waitUntil(timeoutMillis = 5_000) { desktop.state.value.peers.size == 1 && !desktop.state.value.busy }
                onNodeWithText("Android phone").assertExists()
                onNodeWithText("Enter pairing code").assertDoesNotExist()
                onNodeWithContentDescription("Sync with Android phone").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.tracks.first { it.id == desktopTrack.id }.favorite }
                runBlocking { phoneLibrary.setFavorite(phoneTrack.id, false) }
                onNodeWithContentDescription("Sync with Android phone").performClick()
                waitUntil(timeoutMillis = 5_000) { !controller.state.value.tracks.first { it.id == desktopTrack.id }.favorite }
                onNodeWithContentDescription("Remove Android phone").performClick()
                onNodeWithText("Remove device").performClick()
                waitUntil(timeoutMillis = 5_000) { desktop.state.value.peers.isEmpty() }
                assertEquals(null, controller.state.value.error)
            }
        } finally {
            runBlocking { controller.closeAndJoin(); phone.closeAndJoin(); desktop.closeAndJoin() }
            phoneStore.close(); desktopStore.close(); directory.toFile().deleteRecursively()
        }
    }
}
