package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberSystemAudioOutput(): () -> Boolean = remember { {
    try { ProcessBuilder("kcmshell6", "kcm_pulseaudio").start(); true }
    catch (_: java.io.IOException) {
        try { ProcessBuilder("pavucontrol").start(); true } catch (_: java.io.IOException) { false }
    }
} }
