package io.github.d3sox.musicplayer.artwork

import io.github.d3sox.musicplayer.navidrome.NavidromeClient
import io.github.d3sox.musicplayer.navidrome.ServerCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import io.github.d3sox.musicplayer.library.LocalFiles
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.library.TrackMetadata
import io.github.d3sox.musicplayer.library.TrackSource
import io.github.d3sox.musicplayer.library.SourceKind
import io.github.d3sox.musicplayer.storage.openMusicStore
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.images.ArtworkFactory
import java.nio.file.Path
import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.navidrome.NavidromeConnection
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.security.SecretServiceVault
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class ArtworkLibraryTest {
    @Test
    fun artworkArrivingDuringLibraryUseAppearsWithoutWaitingForAnotherRefresh() = runBlocking {
        val directory = Files.createTempDirectory("progressive-library-cover")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val covers = ArtworkLibrary(directory.resolve("artwork").toString())
        val track = library.importTrack(TrackMetadata("Cover arrives later"), TrackSource("later-cover", SourceKind.LOCAL, "/music/song.flac"))
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), artwork = covers)
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
            covers.save(track.id, bytes)
            // Nothing triggers another refresh here, so a generous limit only absorbs slow CI runners.
            val visible = withTimeout(5_000) { controller.state.first { it.artwork[track.id] != null } }
            assertEquals(covers.path(track.id), visible.artwork[track.id])
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aLargeEmbeddedCoverDoesNotAbortScanningAndRemainsAvailableForDisplay() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("large-embedded-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val image = BufferedImage(1_800, 1_800, BufferedImage.TYPE_INT_RGB)
            val random = java.util.Random(42)
            for (x in 0 until image.width) for (y in 0 until image.height) image.setRGB(x, y, random.nextInt(0x1000000))
            val cover = directory.resolve("large.png").toFile()
            ImageIO.write(image, "png", cover)
            kotlin.test.assertTrue(cover.length() > 8 * 1024 * 1024)
            val fixture = Path.of(System.getProperty("musicplayer.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("01-with-cover.flac").toFile()
            Files.copy(fixture, audio.toPath())
            AudioFileIO.read(audio).also { it.tag.setField(ArtworkFactory.createArtworkFromFile(cover)); it.commit() }
            Files.copy(fixture, directory.resolve("02-without-cover.flac"))
            val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
            val tracks = LocalFiles(MusicLibrary(store), artwork).scan(directory.toString())
            assertEquals(2, tracks.size)
            val saved = requireNotNull(artwork.read(tracks.single { it.sources.single().location == audio.canonicalPath }.id))
            kotlin.test.assertNotNull(ImageIO.read(ByteArrayInputStream(saved)))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun connectingAServerRetainsItsCatalogueArtworkForOfflineBrowsing() = runBlocking {
        val directory = Files.createTempDirectory("catalogue-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        val png = output.toByteArray()
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/rest/getCoverArt.view")
                respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
            else {
                val songs = if (request.url.parameters["songOffset"] == "0")
                    """[{"id":"song-a","title":"Server Cover","coverArt":"album-cover"}]""" else "[]"
                respond("""{"subsonic-response":{"status":"ok","searchResult3":{"song":$songs}}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val vault = SecretServiceVault("io.github.d3sox.musicplayer.tests.${Uuid.random()}")
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            NavidromeConnection(store, http, vault), artwork = artwork)
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.connectServer("https://music.example.test", "fixture", "fixture-password").join()
            val track = library.tracks().single()
            http.close()
            assertContentEquals(png, ArtworkLibrary(directory.resolve("artwork").toString()).read(track.id))
        } finally { controller.close(); vault.delete("navidrome"); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun scanningLocalMusicRetainsItsEmbeddedCover() = runBlocking {
        val directory = Files.createTempDirectory("embedded-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
            for (x in 0..31) for (y in 0..31) image.setRGB(x, y, 0x00E05040)
            val cover = directory.resolve("cover.png").toFile()
            ImageIO.write(image, "png", cover)
            val audio = directory.resolve("song.flac").toFile()
            Files.copy(Path.of(System.getProperty("musicplayer.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac"), audio.toPath())
            val tagged = AudioFileIO.read(audio)
            tagged.tag.setField(ArtworkFactory.createArtworkFromFile(cover))
            AudioFileIO.write(tagged)
            val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
            val track = LocalFiles(MusicLibrary(store), artwork).scan(directory.toString()).single()
            assertContentEquals(cover.readBytes(), artwork.read(track.id))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aServerCoverRemainsAvailableOfflineAfterTheAppRestarts() = runBlocking {
        val directory = Files.createTempDirectory("offline-artwork")
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        for (x in 0..31) for (y in 0..31) image.setRGB(x, y, 0x00E05040)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        val png = output.toByteArray()
        val http = HttpClient(MockEngine { request ->
            assertEquals("/rest/getCoverArt.view", request.url.encodedPath)
            assertEquals("album-cover", request.url.parameters["id"])
            respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "fixture-password"))
            ArtworkLibrary(directory.toString()).fetch("server:track", client, "album-cover")
            http.close()
            val reopened = ArtworkLibrary(directory.toString())
            assertContentEquals(png, reopened.read("server:track"))
        } finally { http.close(); directory.toFile().deleteRecursively() }
    }
}
