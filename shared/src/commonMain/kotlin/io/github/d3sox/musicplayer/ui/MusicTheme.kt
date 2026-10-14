package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import io.github.d3sox.musicplayer.settings.ThemeMode
import io.github.d3sox.musicplayer.settings.MusicSettings
import io.github.d3sox.musicplayer.settings.ColorMode

@Composable
internal expect fun platformColorScheme(dark: Boolean): ColorScheme?

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MusicTheme(settings: MusicSettings, artworkColor: Color?, content: @Composable () -> Unit) {
    val mode = settings.themeMode
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val platform = if (settings.colors == ColorMode.SYSTEM) platformColorScheme(dark) else null
    val seed = when (settings.colors) {
        ColorMode.ARTWORK -> artworkColor ?: Color(0xFF6750A4)
        ColorMode.CUSTOM -> Color(settings.customColor)
        ColorMode.SYSTEM -> Color(0xFF6750A4)
    }
    val scheme = remember(platform, dark, settings.amoledBlack, seed) {
        val generated = platform ?: dynamicColorScheme(seedColor = seed, isDark = dark,
            style = PaletteStyle.Expressive, specVersion = ColorSpec.SpecVersion.SPEC_2025)
        val accented = generated.copy(secondary = generated.primary, onSecondary = generated.onPrimary,
            secondaryContainer = generated.primaryContainer, onSecondaryContainer = generated.onPrimaryContainer)
        if (dark && settings.amoledBlack) accented.copy(background = Color.Black, surface = Color.Black,
            surfaceContainerLowest = Color.Black) else accented
    }
    MaterialExpressiveTheme(colorScheme = scheme, content = content)
}

@Composable
internal fun appSliderColors() = androidx.compose.material3.SliderDefaults.colors(
    inactiveTrackColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
    inactiveTickColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
    disabledInactiveTrackColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer.copy(alpha = .38f),
)
