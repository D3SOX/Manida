package io.github.d3sox.musicplayer.ui

import kotlinx.coroutines.*
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

@DBusInterfaceName("org.freedesktop.portal.FileChooser")
interface TestPortalChooser : DBusInterface {
    fun OpenFile(parent: String, title: String, options: Map<String, Variant<*>>): DBusPath
    fun SaveFile(parent: String, title: String, options: Map<String, Variant<*>>): DBusPath
}
@DBusInterfaceName("org.freedesktop.portal.Request")
interface TestPortalRequest : DBusInterface {
    fun Close()
    class Response(path: String, val response: UInt32, val results: Map<String, Variant<*>>) : DBusSignal(path, response, results)
}

private class PortalFixture : AutoCloseable {
    val directory = Files.createTempDirectory("manida-portal")
    private val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=unix:path=${directory.resolve("bus")}")
        .redirectError(directory.resolve("bus.log").toFile()).start()
    val address = daemon.inputStream.bufferedReader().readLine()
    private val server = DBusConnectionBuilder.forAddress(address).withShared(false).build()
    data class Request(val title: String, val save: Boolean, val options: Map<String, Variant<*>>)
    val requests = kotlinx.coroutines.channels.Channel<Request>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val closed = CompletableDeferred<Unit>()
    @Volatile var selected: java.nio.file.Path? = null
    @Volatile var hold = false
    init {
        server.exportObject("/org/freedesktop/portal/desktop", object : TestPortalChooser {
            override fun getObjectPath() = "/org/freedesktop/portal/desktop"
            override fun OpenFile(parent: String, title: String, options: Map<String, Variant<*>>) = select(title, false, options)
            override fun SaveFile(parent: String, title: String, options: Map<String, Variant<*>>) = select(title, true, options)
            private fun select(title: String, save: Boolean, options: Map<String, Variant<*>>): DBusPath {
                val sender = DBusConnection.getCallInfo().source.removePrefix(":").replace('.', '_')
                val path = "/org/freedesktop/portal/desktop/request/$sender/${options.getValue("handle_token").value}"
                if (hold) server.exportObject(path, object : TestPortalRequest {
                    override fun getObjectPath() = path
                    override fun Close() { closed.complete(Unit) }
                })
                else {
                    val results = selected?.let { mapOf("uris" to Variant(listOf(it.toUri().toString()), "as")) }.orEmpty()
                    // Exercise the early-response race through real D-Bus serialization.
                    server.sendMessage(TestPortalRequest.Response(path, UInt32(if (selected == null) 1 else 0), results))
                }
                requests.trySend(Request(title, save, options))
                return DBusPath(path)
            }
        })
        server.requestBusName("org.freedesktop.portal.Desktop")
    }
    override fun close() {
        server.close(); daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
        directory.toFile().deleteRecursively()
    }
}

class DesktopPortalTest {
    @Test fun foldersAreSelectedThroughTheDesktopPortalAndFileUrisAreDecoded() = runBlocking {
        PortalFixture().use { portal ->
            val folder = Files.createDirectory(portal.directory.resolve("Música #1"))
            portal.selected = folder
            assertEquals(folder.toString(), withTimeout(2_000) { DesktopFolderPicker(portal.address).pick() })
            assertEquals(true, portal.requests.receive().options.getValue("directory").value)
        }
    }

    @Test fun artworkPlaylistsAndEqualizerProfilesUseNativeOpenAndSaveRequests() = runBlocking {
        PortalFixture().use { portal ->
            val picture = portal.directory.resolve("cover.png")
            val image = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
            image.setRGB(16, 16, 0xE05040)
            javax.imageio.ImageIO.write(image, "png", picture.toFile())
            portal.selected = picture
            val chosen = withTimeout(2_000) { DesktopArtworkPicker(portal.address).pick() }
            assertEquals(0xE05040, javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(chosen!!)).getRGB(16, 16) and 0xFFFFFF)
            assertEquals("a(sa(us))", portal.requests.receive().options.getValue("filters").sig)

            val playlist = portal.directory.resolve("Mix #1.m3u8")
            Files.writeString(playlist, "#EXTM3U\n/music/ROSALÍA.flac\n")
            portal.selected = playlist
            val files = DesktopPlaylistFiles(portal.address)
            val imported = withTimeout(2_000) { files.open() }!!
            assertEquals("Mix #1", imported.name)
            assertEquals(Files.readString(playlist), imported.text)
            assertEquals(portal.directory.toString(), imported.baseLocation)
            assertFalse(portal.requests.receive().save)
            assertTrue(withTimeout(2_000) { files.save("New mix.m3u8", "#EXTM3U\n/music/new.flac\n") })
            val saved = portal.requests.receive()
            assertTrue(saved.save)
            assertEquals("New mix.m3u8", saved.options.getValue("current_name").value)
            assertEquals("#EXTM3U\n/music/new.flac\n", Files.readString(playlist))

            val equalizer = portal.directory.resolve("Headphones ParametricEQ.txt")
            Files.writeString(equalizer, "Preamp: -3 dB\nFilter 1: ON PK Fc 100 Hz Gain 2 dB Q 1.4\n")
            portal.selected = equalizer
            val profile = withTimeout(2_000) { DesktopEqualizerFiles(portal.address).open() }!!
            assertEquals("Headphones", profile.name)
            assertEquals(Files.readString(equalizer), profile.text)
            assertEquals("Import equalizer profile", portal.requests.receive().title)
        }
    }

    @Test fun cancellingEitherSideLeavesFilesUntouchedAndClosesAnOutstandingPortalRequest() = runBlocking {
        PortalFixture().use { portal ->
            assertNull(withTimeout(2_000) { DesktopFolderPicker(portal.address).pick() })
            portal.requests.receive()
            val playlist = portal.directory.resolve("keep.m3u8")
            Files.writeString(playlist, "Keep this playlist")
            assertFalse(withTimeout(2_000) { DesktopPlaylistFiles(portal.address).save("keep.m3u8", "Replace") })
            assertEquals("Keep this playlist", Files.readString(playlist))
            portal.requests.receive()
            portal.hold = true
            val pick = async { DesktopFolderPicker(portal.address).pick() }
            withTimeout(2_000) { portal.requests.receive() }
            pick.cancelAndJoin()
            withTimeout(2_000) { portal.closed.await() }
        }
    }
}
