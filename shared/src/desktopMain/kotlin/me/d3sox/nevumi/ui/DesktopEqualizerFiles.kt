package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberEqualizerFiles(): EqualizerFiles = remember { DesktopEqualizerFiles() }

class DesktopEqualizerFiles(address: String? = null) : EqualizerFiles {
    private val portal = PortalFileChooser(address)
    override suspend fun open(): EqualizerDocument? {
        val file = portal.choose("Import equalizer profile", filter = portalFilter("Equalizer APO text", "*.[tT][xX][tT]")) ?: return null
        return withContext(Dispatchers.IO) {
            EqualizerDocument(file.nameWithoutExtension.removeSuffix(" ParametricEQ").removeSuffix(" FixedBandEQ"),
                file.inputStream().use(::readEqualizer))
        }
    }
}
