package me.d3sox.nevumi.sftp

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.playlists.Playlists
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import me.d3sox.nevumi.navidrome.NavidromeRequestException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SftpConnectionTest {
    @Test
    fun retryingAnUploadRejectsADamagedStagedCopyWithoutPublishingIt() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val album = music.resolve("Fixture Artist/Fixture Album")
        val permissions = Files.getPosixFilePermissions(album)
        val relative = "Fixture Artist/Fixture Album/upload-damaged.flac"
        val destination = music.resolve(relative)
        val directory = Files.createTempDirectory("nevumi-sftp-damaged-upload")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        try {
            val local = Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.flac"), directory.resolve("local.flac"))
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(local.toString()).single()
            val connection = SftpConnection(store, library, { client }, vault, directory.resolve("backups").toString())
            connection.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(), Files.readString(fixture.resolve("fingerprint"))),
                1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            Files.setPosixFilePermissions(album, permissions - java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
            assertFailsWith<SftpFailure> { connection.upload(track.id, relative) }
            Files.setPosixFilePermissions(album, permissions)
            val pending = connection.pendingUploads().single()
            Files.write(Path.of(pending.stagedPath), byteArrayOf(1, 2, 3, 4))
            val failure = assertFailsWith<IllegalStateException> { connection.retryUpload(pending.id) }
            assertEquals("The staged upload is unavailable or damaged. Discard it and upload the song again.", failure.message)
            assertFalse(Files.exists(destination))
            assertEquals(listOf(pending), connection.pendingUploads())
            Unit
        } finally {
            Files.setPosixFilePermissions(album, permissions)
            Files.deleteIfExists(destination)
            client.rescan()
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun retryingAnInterruptedTransferReclaimsOnlyItsOwnPartialServerFile() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val album = music.resolve("Fixture Artist/Fixture Album")
        val permissions = Files.getPosixFilePermissions(album)
        val relative = "Fixture Artist/Fixture Album/upload-partial.flac"
        val destination = music.resolve(relative)
        val directory = Files.createTempDirectory("nevumi-sftp-partial-upload")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        var partial: Path? = null
        val unrelated = album.resolve(".nevumi-00000000-0000-0000-0000-000000000001.part")
        try {
            val local = Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.flac"), directory.resolve("local.flac"))
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(local.toString()).single()
            val connection = SftpConnection(store, library, { client }, vault, directory.resolve("backups").toString())
            connection.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(), Files.readString(fixture.resolve("fingerprint"))),
                1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            Files.setPosixFilePermissions(album, permissions - java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
            assertFailsWith<SftpFailure> { connection.upload(track.id, relative) }
            Files.setPosixFilePermissions(album, permissions)
            val pending = connection.pendingUploads().single()
            partial = album.resolve(".nevumi-${pending.id}.part")
            Files.write(partial, byteArrayOf(1, 2, 3, 4))
            Files.write(unrelated, byteArrayOf(9, 8, 7))
            val result = connection.retryUpload(pending.id)
            assertEquals(track.id, result.id)
            assertEquals(-1L, Files.mismatch(local, destination))
            assertFalse(Files.exists(partial))
            kotlin.test.assertContentEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(unrelated))
            assertTrue(connection.pendingUploads().isEmpty())
            Unit
        } finally {
            Files.setPosixFilePermissions(album, permissions)
            Files.deleteIfExists(destination)
            partial?.let { Files.deleteIfExists(it) }
            Files.deleteIfExists(unrelated)
            client.rescan()
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun anUnfinishedUploadCanBeDiscardedAfterDisconnectWithoutDeletingThePublishedMusicFile() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val relative = "Fixture Artist/Fixture Album/upload-discard.flac"
        val destination = music.resolve(relative)
        val directory = Files.createTempDirectory("nevumi-sftp-upload-discard")
        val store = openMusicStore(directory.resolve("library.db").toString())
        var failScan = false
        val http = HttpClient(OkHttp) {
            install(createClientPlugin("FixtureScanFailure") {
                onRequest { request, _ -> if (failScan && request.url.build().encodedPath.endsWith("/rest/startScan.view")) throw IOException("Fixture disconnected") }
            })
        }
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        try {
            val original = Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.flac"), directory.resolve("local.flac"))
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(original.toString()).single()
            val connection = SftpConnection(store, library, { client }, vault, directory.resolve("backups").toString())
            connection.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
                Files.readString(fixture.resolve("fingerprint"))), 1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            failScan = true
            assertFailsWith<NavidromeRequestException> { connection.upload(track.id, relative) }
            val pending = connection.pendingUploads().single()
            connection.disconnect()
            connection.discardUpload(pending.id)
            assertTrue(connection.pendingUploads().isEmpty())
            assertEquals(false, Files.exists(Path.of(pending.stagedPath)))
            assertEquals(-1L, Files.mismatch(original, destination))
            Unit
        } finally {
            failScan = false
            Files.deleteIfExists(destination)
            client.rescan()
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun anEmptyNavidromeMusicLibraryIsAvailableForSftpSetup() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-empty-server-directory")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/auth/login") -> """{"token":"fixture-session"}"""
                request.url.encodedPath.endsWith("/api/song") -> "[]"
                else -> """{"subsonic-response":{"status":"ok","musicFolders":{"musicFolder":[{"id":1,"name":"Empty music library"}]}}}"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) = Unit
            override suspend fun read(key: String): String? = null
            override suspend fun delete(key: String) = Unit
        }
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "fixture-password"))
            val connection = SftpConnection(store, MusicLibrary(store), { client }, vault, directory.resolve("backups").toString())
            assertEquals(listOf(ServerMusicDirectory(1, "Empty music library")), connection.libraries())
            Unit
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aCompletedUploadCanFinishIndexingAfterRestartWithoutItsOriginalLocalFile() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val music = root.resolve(".test-environment/sftp-music")
        val relative = "Fixture Artist/Fixture Album/upload-retry.flac"
        val uploaded = music.resolve(relative)
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val directory = Files.createTempDirectory("nevumi-sftp-upload-restart")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var failScan = false
        val http = HttpClient(OkHttp) {
            install(createClientPlugin("FixtureScanFailure") {
                onRequest { request, _ ->
                    if (failScan && request.url.build().encodedPath.endsWith("/rest/startScan.view")) throw IOException("Fixture disconnected")
                }
            })
        }
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        try {
            val local = Files.createDirectory(directory.resolve("local"))
            val original = Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.flac"), local.resolve("upload.flac"))
            val audio = AudioFileIO.read(original.toFile())
            audio.tag.deleteField(FieldKey.MUSICBRAINZ_TRACK_ID)
            audio.tag.deleteField(FieldKey.MUSICBRAINZ_RELEASEID)
            audio.tag.setField(FieldKey.TITLE, "Restarted upload")
            audio.commit()
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(local.toString()).single()
            val connection = SftpConnection(store, library, { client }, vault, directory.resolve("backups").toString())
            connection.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
                Files.readString(fixture.resolve("fingerprint"))), 1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            failScan = true
            assertFailsWith<NavidromeRequestException> { connection.upload(track.id, relative) }
            val pending = connection.pendingUploads().single()
            assertEquals(track.id, pending.trackId)
            assertEquals(relative, pending.relativePath)
            assertEquals(-1L, Files.mismatch(original, uploaded))
            Files.delete(original)
            store.close(); store = openMusicStore(database)
            failScan = false
            val reopened = MusicLibrary(store)
            val recovered = SftpConnection(store, reopened, { client }, vault, directory.resolve("backups").toString())
            assertEquals(listOf(pending), recovered.pendingUploads())
            val result = recovered.retryUpload(pending.id)
            assertEquals(track.id, result.id)
            assertEquals(relative, result.sources.single { it.kind == SourceKind.SERVER }.filePath)
            assertEquals(4, reopened.tracks().size)
            assertEquals(1, client.musicFiles().count { it.path == relative })
            assertTrue(recovered.pendingUploads().isEmpty())
            Unit
        } finally {
            failScan = false
            Files.deleteIfExists(uploaded)
            client.rescan()
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aPendingUploadFollowsTheChosenCanonicalSongWhenCopiesAreLinked() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val music = root.resolve(".test-environment/sftp-music")
        val relative = "Fixture Artist/Fixture Album/upload-linked-retry.flac"
        val uploaded = music.resolve(relative)
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val directory = Files.createTempDirectory("nevumi-sftp-upload-linked-restart")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var failScan = false
        val http = HttpClient(OkHttp) {
            install(createClientPlugin("FixtureScanFailure") {
                onRequest { request, _ ->
                    if (failScan && request.url.build().encodedPath.endsWith("/rest/startScan.view")) throw IOException("Fixture disconnected")
                }
            })
        }
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        try {
            val local = Files.createDirectory(directory.resolve("local"))
            val original = Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.flac"), local.resolve("upload.flac"))
            val audio = AudioFileIO.read(original.toFile())
            audio.tag.deleteField(FieldKey.MUSICBRAINZ_TRACK_ID)
            audio.tag.deleteField(FieldKey.MUSICBRAINZ_RELEASEID)
            audio.tag.setField(FieldKey.TITLE, "Restarted upload")
            audio.commit()
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(local.toString()).single()
            val connection = SftpConnection(store, library, { client }, vault, directory.resolve("backups").toString())
            connection.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
                Files.readString(fixture.resolve("fingerprint"))), 1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            failScan = true
            assertFailsWith<NavidromeRequestException> { connection.upload(track.id, relative) }
            val pending = connection.pendingUploads().single()
            assertEquals(track.id, pending.trackId)
            assertEquals(relative, pending.relativePath)
            assertEquals(-1L, Files.mismatch(original, uploaded))
            val survivor = LocalFiles(library).scan(Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.flac"),
                directory.resolve("survivor.flac")).toString()).single()
            library.linkTracks(survivor.id, track.id)
            val remapped = connection.pendingUploads().single()
            assertEquals(survivor.id, remapped.trackId)
            Files.delete(original)
            store.close(); store = openMusicStore(database)
            failScan = false
            val reopened = MusicLibrary(store)
            val recovered = SftpConnection(store, reopened, { client }, vault, directory.resolve("backups").toString())
            assertEquals(listOf(remapped), recovered.pendingUploads())
            val result = recovered.retryUpload(remapped.id)
            assertEquals(survivor.id, result.id)
            assertEquals(relative, result.sources.single { it.kind == SourceKind.SERVER }.filePath)
            assertEquals(4, reopened.tracks().size)
            assertEquals(1, client.musicFiles().count { it.path == relative })
            assertTrue(recovered.pendingUploads().isEmpty())
            Unit
        } finally {
            failScan = false
            Files.deleteIfExists(uploaded)
            client.rescan()
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun uploadingALocalSongWithoutMusicBrainzIdsKeepsItsFavoriteQueuePlaylistAndCanonicalIdentity() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val music = root.resolve(".test-environment/sftp-music")
        val relative = "Fixture Artist/Fixture Album/uploaded-local.mp3"
        val uploaded = music.resolve(relative)
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val directory = Files.createTempDirectory("nevumi-sftp-library-upload")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        try {
            val local = Files.createDirectory(directory.resolve("local"))
            val original = Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.mp3"), local.resolve("upload.mp3"))
            val audio = AudioFileIO.read(original.toFile())
            audio.tag.deleteField(FieldKey.MUSICBRAINZ_TRACK_ID)
            audio.tag.deleteField(FieldKey.MUSICBRAINZ_RELEASEID)
            audio.tag.setField(FieldKey.TITLE, "Uploaded local song")
            audio.commit()
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(local.toString()).single()
            assertEquals("", track.metadata.recordingId)
            library.setFavorite(track.id, true)
            library.setRating(track.id, 4)
            val queue = PlaybackQueue(store)
            queue.replace(listOf(track.id, track.id))
            val playlists = Playlists(store)
            val playlist = playlists.create("My local song", listOf(track.id))
            val connection = SftpConnection(store, library, { client }, vault, directory.resolve("backups").toString())
            connection.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
                Files.readString(fixture.resolve("fingerprint"))), 1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            val result = connection.upload(track.id, relative)
            assertEquals(track.id, result.id)
            assertEquals(setOf(SourceKind.LOCAL, SourceKind.SERVER), result.sources.map { it.kind }.toSet())
            assertEquals(-1L, Files.mismatch(original, uploaded))
            assertEquals(true, result.favorite)
            assertEquals(4, result.rating)
            assertEquals(4, library.tracks().size)
            assertEquals(listOf(track.id, track.id), queue.snapshot().entries.map { it.trackId })
            assertEquals(listOf(track.id), playlists.get(playlist.id)!!.trackIds)
            NavidromeChanges(store, library) { client }.use { it.synchronize() }
            val serverSong = client.songs(includeFilePaths = true).single { it.filePath == relative }
            assertEquals(true, serverSong.favorite)
            assertEquals(4, serverSong.rating)
            Unit
        } finally {
            Files.deleteIfExists(uploaded)
            client.rescan()
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aDirectoryWithDifferentMusicCannotBeSavedAsTheNavidromeFileConnection() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val directory = Files.createTempDirectory("nevumi-sftp-wrong-directory")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        try {
            val music = Files.createDirectory(directory.resolve("wrong-music"))
            for (file in client.musicFiles()) {
                val target = music.resolve(file.path)
                Files.createDirectories(target.parent)
                Files.writeString(target, "Different music with the same filename")
            }
            val connection = SftpConnection(store, MusicLibrary(store), { client }, vault, directory.resolve("backups").toString())
            val profile = SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
                Files.readString(fixture.resolve("fingerprint")))
            assertFailsWith<IllegalStateException> {
                connection.connect(profile, 1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            }
            assertEquals(null, connection.configuration())
            assertTrue(secrets.isEmpty())
            Unit
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun configuredServerEditsAndTheirUndoSurviveRestartAndRequireTheOriginalConnection() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val music = root.resolve(".test-environment/sftp-music")
        val file = music.resolve("Fixture Artist/Fixture Album/01 Northern Light.flac")
        val original = root.resolve("androidApp/src/androidTest/assets/fixture.flac")
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val directory = Files.createTempDirectory("nevumi-sftp-configured-edit")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        try {
            client.rescan()
            val library = MusicLibrary(store)
            val track = NavidromeChanges(store, library) { client }.refreshLibrary(client)
                .first { it.first.metadata.title == "Northern Light" }.first
            val profile = SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
                Files.readString(fixture.resolve("fingerprint")))
            val authentication = SftpAuthentication(privateKey = Files.readString(fixture.resolve("client")))
            val connection = SftpConnection(store, library, { client }, vault, directory.resolve("backups").toString())
            connection.connect(profile, 1, authentication)
            assertEquals("Northern Light", connection.readTags(track.id).title)
            val edit = connection.editTags(track.id, track.metadata.copy(title = "Configured server edit"))
            assertEquals("Configured server edit", client.songs().first { it.id == track.sources.single().location }.metadata.title)
            store.close(); store = openMusicStore(database)
            val reopened = SftpConnection(store, MusicLibrary(store), { client }, vault, directory.resolve("backups").toString())
            assertEquals(edit.id, reopened.history().single().id)
            reopened.disconnect()
            assertFailsWith<IllegalStateException> { reopened.undo(edit.id) }
            assertEquals("Configured server edit", client.songs().first { it.id == track.sources.single().location }.metadata.title)
            reopened.connect(profile, 1, authentication)
            reopened.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, file))
            assertEquals(true, reopened.history().single().undone)
            Unit
        } finally {
            Files.copy(original, file, StandardCopyOption.REPLACE_EXISTING)
            client.rescan()
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aVerifiedConnectionKeepsItsConfigurationAndVaultCredentialsAcrossRestart() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val directory = Files.createTempDirectory("nevumi-sftp-connection")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        try {
            val profile = SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), root.resolve(".test-environment/sftp-music").toString(),
                Files.readString(fixture.resolve("fingerprint")))
            val key = Files.readString(fixture.resolve("client"))
            val connection = SftpConnection(store, MusicLibrary(store), { client }, vault, directory.resolve("backups").toString())
            connection.connect(profile, 1, SftpAuthentication(privateKey = key))
            assertEquals(profile, connection.configuration()!!.connection)
            assertEquals(client.serverId, connection.configuration()!!.serverId)
            assertTrue(secrets.values.any { it.contains("BEGIN OPENSSH PRIVATE KEY") })
            store.close(); store = openMusicStore(database)
            val reopened = SftpConnection(store, MusicLibrary(store), { client }, vault, directory.resolve("backups").toString())
            assertEquals(profile, reopened.configuration()!!.connection)
            assertEquals(listOf(ServerMusicDirectory(1, "Music Library")), reopened.libraries())
            for (file in directory.toFile().listFiles().orEmpty().filter { it.name.startsWith("library.db") })
                assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("BEGIN OPENSSH PRIVATE KEY"))
            reopened.disconnect()
            assertEquals(null, reopened.configuration())
            assertTrue(secrets.isEmpty())
            Unit
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
