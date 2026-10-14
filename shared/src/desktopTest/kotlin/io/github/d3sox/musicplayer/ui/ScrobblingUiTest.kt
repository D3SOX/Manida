package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.history.*
import io.github.d3sox.musicplayer.library.*
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.security.SecretServiceVault
import io.github.d3sox.musicplayer.settings.MusicPreferences
import io.github.d3sox.musicplayer.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.uuid.Uuid
import java.io.IOException
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.ktor.http.content.OutgoingContent
import kotlinx.serialization.json.*
import io.ktor.client.request.HttpRequestData

@OptIn(ExperimentalTestApi::class)
class ScrobblingUiTest {
    @Test
    fun lastFmSignInOpensTheAuthorizationPageAndKeepsThePendingSignInWhenAuthorizationIsNotGrantedYet() {
        val directory = Files.createTempDirectory("lastfm-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val vault = SecretServiceVault("io.github.d3sox.musicplayer.tests.${Uuid.random()}")
        val opened = mutableListOf<String>()
        var authorized = false
        val http = HttpClient(MockEngine { request ->
            val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            respond(when (form["method"]) {
                "auth.getToken" -> """{"token":"fixture-authorization"}"""
                "auth.getSession" -> if (authorized) """{"session":{"name":"fixture","key":"fixture-session"}}"""
                    else """{"error":14,"message":"Token not authorized"}"""
                else -> error("Unexpected Last.fm method")
            }, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val service = Scrobbling(store, library, http, vault)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            listeningHistory = ListeningHistory(store, library), preferences = MusicPreferences(store), scrobbling = service)
        try {
            runComposeUiTest {
                setContent { CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) { MusicPlayerApp(controller) } }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithText("Connect Last.fm").performClick()
                onNodeWithText("Application API key").performTextInput("fixture-api-key")
                onNodeWithText("Application shared secret").performTextInput("fixture-shared-secret")
                onNodeWithText("Start sign-in").performClick()
                waitUntil(timeoutMillis = 3_000) { opened.size == 1 }
                assertEquals("www.last.fm", Url(opened.single()).host)
                assertEquals("fixture-authorization", Url(opened.single()).parameters["token"])
                assertFalse(opened.single().contains("fixture-shared-secret"))
                onNodeWithText("Complete sign-in").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Authorize Manida in Last.fm, then complete sign-in.").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(controller.state.value.scrobbling.profiles.isEmpty())
                onNodeWithText("Open Last.fm authorization").assertExists()
                authorized = true
                onNodeWithText("Complete sign-in").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.scrobbling.profiles.size == 1 }
                onNodeWithText("Connected as fixture").assertIsDisplayed()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Complete sign-in").fetchSemanticsNodes().isEmpty() }
                assertEquals(ScrobblingService.LASTFM, controller.state.value.scrobbling.profiles.single().service)
                onNodeWithText("Connect Last.fm").performClick()
                onNodeWithText("Application API key").performTextInput("fixture-api-key")
                onNodeWithText("Application shared secret").performTextInput("fixture-shared-secret")
                onNodeWithText("Start sign-in").performClick()
                waitUntil(timeoutMillis = 3_000) { opened.size == 2 }
                onNodeWithText("Cancel sign-in").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Complete sign-in").fetchSemanticsNodes().isEmpty() }
                assertNull(runBlocking { service.pendingLastFmAuthorization() })
                assertEquals(1, controller.state.value.scrobbling.profiles.size)
            }
        } finally {
            runBlocking { controller.closeAndJoin(); vault.delete("lastfm:authorization"); service.profiles().forEach { vault.delete("scrobbling:${it.id}") } }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun anUnconfirmedListenCanBeReviewedAndOnlyRetriedAfterConfirmingThePossibleDuplicate() {
        val directory = Files.createTempDirectory("uncertain-scrobbling-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val vault = SecretServiceVault("io.github.d3sox.musicplayer.tests.${Uuid.random()}")
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"fixture"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else {
                if (listenType(request) == "single") {
                    requests++
                    if (requests == 1) throw IOException("Lost confirmation")
                }
                respond("""{"status":"ok"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val service = Scrobbling(store, library, http, vault)
        runBlocking {
            service.connectListenBrainz("fixture-token")
            library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", durationMs = 3_000),
                TrackSource("local", SourceKind.LOCAL, Path.of(System.getProperty("musicplayer.testRoot"), ".test-environment",
                    "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            listeningHistory = ListeningHistory(store, library), preferences = MusicPreferences(store), scrobbling = service)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 6_000) { controller.state.value.playback.ended && controller.state.value.scrobbling.submissions.singleOrNull()?.state == ScrobbleState.UNCERTAIN }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithText("Unconfirmed submission").assertIsDisplayed()
                onNodeWithText("Retry").performScrollTo().performClick()
                onNodeWithText("Retry unconfirmed listen?").assertIsDisplayed()
                onNodeWithText("Cancel").performClick()
                assertEquals(1, requests)
                onNodeWithText("Retry").performScrollTo().performClick()
                onNodeWithText("Retry listen").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.scrobbling.submissions.single().state == ScrobbleState.CONFIRMED }
                assertEquals(2, requests)
                onNodeWithText("No pending listens").assertIsDisplayed()
            }
        } finally {
            runBlocking { controller.closeAndJoin(); service.profiles().forEach { vault.delete("scrobbling:${it.id}") } }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun connectingListenBrainzThroughSettingsScrobblesARealSongAndCanBeDisabled() {
        val directory = Files.createTempDirectory("scrobbling-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val vault = SecretServiceVault("io.github.d3sox.musicplayer.tests.${Uuid.random()}")
        var requests = 0
        var nowPlaying = 0
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"fixture"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else {
                if (listenType(request) == "single") requests++ else nowPlaying++
                respond("""{"status":"ok"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val scrobbling = Scrobbling(store, library, http, vault)
        runBlocking {
            library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", durationMs = 3_000),
                TrackSource("local", SourceKind.LOCAL, Path.of(System.getProperty("musicplayer.testRoot"), ".test-environment",
                    "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            listeningHistory = ListeningHistory(store, library), preferences = MusicPreferences(store), scrobbling = scrobbling)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithText("Connect ListenBrainz").performClick()
                onNodeWithText("User token").performTextInput("fixture-token")
                onNodeWithText("Connect").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.scrobbling.profiles.size == 1 }
                onNodeWithText("Connected as fixture").assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 6_000) { controller.state.value.playback.ended && controller.state.value.scrobbling.submissions.singleOrNull()?.state == ScrobbleState.CONFIRMED }
                assertEquals(1, requests)
                assertEquals(1, nowPlaying)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithContentDescription("Send listens to ListenBrainz").performClick()
                onNode(hasText("Off") and hasAnyAncestor(isPopup())).performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.scrobbling.profiles.single().enabled == false }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                runBlocking { controller.playTrack(controller.state.value.tracks.single().id).join() }
                waitUntil(timeoutMillis = 6_000) { controller.state.value.playback.ended }
                assertEquals(1, requests)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithText("Disconnect ListenBrainz").performScrollTo().performClick()
                onNodeWithText("Cancel").performClick()
                assertEquals(1, controller.state.value.scrobbling.profiles.size)
                onNodeWithText("Disconnect ListenBrainz").performScrollTo().performClick()
                onNodeWithText("Disconnect").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.scrobbling.profiles.isEmpty() }
                assertEquals(2, runBlocking { ListeningHistory(store, library).all() }.size)
            }
        } finally {
            runBlocking { controller.closeAndJoin(); scrobbling.profiles().forEach { vault.delete("scrobbling:${it.id}") } }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }
    private fun listenType(request: HttpRequestData) = Json.parseToJsonElement(
        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject.getValue("listen_type").jsonPrimitive.content
}
