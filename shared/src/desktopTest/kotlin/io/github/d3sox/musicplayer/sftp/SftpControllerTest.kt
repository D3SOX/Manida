package io.github.d3sox.musicplayer.sftp

import io.github.d3sox.musicplayer.app.MusicController
import io.github.d3sox.musicplayer.library.LocalFiles
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.navidrome.NavidromeConnection
import io.github.d3sox.musicplayer.navidrome.ServerCredentials
import io.github.d3sox.musicplayer.playback.MpvPlaybackEngine
import io.github.d3sox.musicplayer.queue.PlaybackQueue
import io.github.d3sox.musicplayer.security.CredentialVault
import io.github.d3sox.musicplayer.settings.MusicPreferences
import io.github.d3sox.musicplayer.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SftpControllerTest {
    @Test
    fun aLockedCredentialStoreDoesNotPreventRestoringLocalPlaybackOrSavedServerSettings() = runBlocking {
        val root = Path.of(System.getProperty("musicplayer.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val directory = Files.createTempDirectory("manida-locked-sftp-startup")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        var locked = false
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String): String? {
                check(!locked) { "Fixture credential store locked" }
                return secrets[key]
            }
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        var controller: MusicController? = null
        try {
            val library = MusicLibrary(store)
            val original = Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.flac"), directory.resolve("local.flac"))
            val local = LocalFiles(library).scan(original.toString()).single()
            val navidrome = NavidromeConnection(store, http, vault)
            navidrome.connect("http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
            val server = navidrome.profile()
            val files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString())
            files.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(), Files.readString(fixture.resolve("fingerprint"))),
                1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            val configuration = files.configuration()
            val queue = PlaybackQueue(store)
            queue.replace(listOf(local.id))
            val preferences = MusicPreferences(store)
            preferences.update { it.copy(speed = 1.25, pitch = 0.9) }
            locked = true
            val player = MusicController(library, queue, MpvPlaybackEngine("null"), navidrome, preferences = preferences, serverFiles = files)
            controller = player
            withTimeout(5_000) { while (player.state.value.error == null) delay(20) }
            assertEquals(server, player.state.value.server)
            assertNotNull(player.state.value.sftp)
            assertEquals(configuration, player.state.value.sftp)
            assertEquals(1.25, player.state.value.preferences.speed)
            withTimeout(5_000) { while (player.state.value.playback.trackId != local.id) delay(20) }
            assertEquals(false, player.engine.status.value.playing)
            Unit
        } finally {
            controller?.closeAndJoin()
            locked = false
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }
    @Test
    fun changingUndoStorageLimitsAlsoExpiresServerFileBackups() = runBlocking {
        val root = Path.of(System.getProperty("musicplayer.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val directory = Files.createTempDirectory("manida-sftp-backup-retention")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String): String? {
                return secrets[key]
            }
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        var controller: MusicController? = null
        try {
            val library = MusicLibrary(store)
            val original = Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.flac"), directory.resolve("local.flac"))
            val local = LocalFiles(library).scan(original.toString()).single()
            val navidrome = NavidromeConnection(store, http, vault)
            navidrome.connect("http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
            val server = navidrome.profile()
            val files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString())
            files.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(), Files.readString(fixture.resolve("fingerprint"))),
                1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            val configuration = files.configuration()
            val serverTrack = library.tracks().first { it.sources.any { source -> source.kind == io.github.d3sox.musicplayer.library.SourceKind.SERVER } }
            val originalServerFile = music.resolve(serverTrack.sources.single().filePath!!)
            val originalBytes = Files.readAllBytes(originalServerFile)
            val edit = files.editTags(serverTrack.id, serverTrack.metadata.copy(title = "Undo retention test"))
            try {
                val preferences = MusicPreferences(store)
                val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), navidrome,
                    preferences = preferences, serverFiles = files)
                controller = player
                withTimeout(5_000) { while (player.state.value.fileEdits.isEmpty()) delay(20) }
                player.updatePreferences { it.copy(undoBackupBytes = 0) }.join()
                assertEquals(null, player.state.value.error)
                assertEquals(emptyList(), files.history())
                assertEquals(false, Files.exists(Path.of(edit.backupPath)))
            } finally {
                Files.write(originalServerFile, originalBytes)
                navidrome.client()!!.rescan()
            }
            Unit
        } finally {
            controller?.closeAndJoin()
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }
}
