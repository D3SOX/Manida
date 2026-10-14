package io.github.d3sox.musicplayer.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@Composable
internal actual fun platformColorScheme(dark: Boolean): ColorScheme? {
    val accent by produceState<Color?>(null) {
        while (currentCoroutineContext().isActive) {
            value = withContext(Dispatchers.IO) { desktopAccent() }
            delay(5_000)
        }
    }
    return remember(accent, dark) {
        accent?.let { dynamicColorScheme(seedColor = it, isDark = dark,
            style = PaletteStyle.Expressive, specVersion = ColorSpec.SpecVersion.SPEC_2025) }
    }
}

private fun desktopAccent(): Color? {
    return try {
        val process = ProcessBuilder("gdbus", "call", "--session", "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop", "--method", "org.freedesktop.portal.Settings.ReadOne",
            "org.freedesktop.appearance", "accent-color").redirectErrorStream(true).start()
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            val response = process.inputStream.use { it.readNBytes(4_096).decodeToString() }.trim()
            val tuple = Regex("\\(<\\(([^)]+)\\)>,\\)").matchEntire(response)?.groupValues?.get(1) ?: return null
            val rgb = tuple.split(',').map { it.trim().toDoubleOrNull() ?: return null }
            if (rgb.size != 3 || rgb.any { !it.isFinite() || it !in 0.0..1.0 }) return null
            Color(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat())
        } finally { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS) }
    } catch (_: Exception) { null }
}
