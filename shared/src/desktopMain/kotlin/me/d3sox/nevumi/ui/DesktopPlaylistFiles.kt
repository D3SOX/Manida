package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Composable
internal actual fun rememberPlaylistFiles(): PlaylistFiles = remember { DesktopPlaylistFiles() }

class DesktopPlaylistFiles(address: String? = null) : PlaylistFiles {
    private val portal = PortalFileChooser(address)
    private val filter = portalFilter("M3U playlists", "*.[mM]3[uU]", "*.[mM]3[uU]8")
    override suspend fun open(): PlaylistDocument? {
        val file = portal.choose("Import playlist", filter = filter) ?: return null
        return withContext(Dispatchers.IO) {
            PlaylistDocument(file.nameWithoutExtension, file.inputStream().use(::readPlaylist), file.absoluteFile.parent)
        }
    }

    override suspend fun save(name: String, text: String): Boolean {
        val file = portal.choose("Export playlist", filter = filter, saveName = name) ?: return false
        withContext(Dispatchers.IO) {
            val temporary = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".nevumi-playlist-", ".tmp")
            try {
                Files.writeString(temporary, text, Charsets.UTF_8)
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        return true
    }
}
