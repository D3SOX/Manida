package io.github.d3sox.musicplayer.ui

import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberSystemAudioOutput(): () -> Boolean {
    val context = LocalContext.current
    return remember(context) { {
        try {
            if (Build.VERSION.SDK_INT >= 34) MediaRouter2.getInstance(context).showSystemOutputSwitcher()
            else { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)); true }
        } catch (_: Exception) { false }
    } }
}
