package me.d3sox.nevumi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PAUSE) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { (context.applicationContext as MusicApplication).controller.setDownloadsPaused(true).join() }
            finally { pending.finish() }
        }
    }

    companion object { const val PAUSE = "me.d3sox.nevumi.PAUSE_DOWNLOADS" }
}
