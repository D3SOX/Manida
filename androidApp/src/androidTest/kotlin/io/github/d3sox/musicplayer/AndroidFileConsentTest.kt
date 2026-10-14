package io.github.d3sox.musicplayer

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.library.AndroidFileEdits
import io.github.d3sox.musicplayer.library.AndroidLocalSources
import io.github.d3sox.musicplayer.library.MusicLibrary
import io.github.d3sox.musicplayer.storage.openMusicStore
import kotlinx.coroutines.*
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidFileConsentTest {
    @Test
    fun changingAFileWhileAndroidEditPermissionIsPendingPreservesTheExternalChange() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        shell("pm grant ${app.packageName} $permission")
        val id = UUID.randomUUID().toString()
        shell("content insert --uri content://media/external/audio/media --bind _display_name:s:fixture.flac --bind relative_path:s:Music/Manida-consent-$id --bind is_pending:i:0")
        val created = shell("content query --uri content://media/external/audio/media --projection _id --where relative_path='Music/Manida-consent-$id/'")
        val row = requireNotNull(Regex("_id=(\\d+)").find(created)) { "The shell-owned music fixture was not created: $created" }.groupValues[1]
        val uri = Uri.parse("content://media/external/audio/media/$row")
        fun writeAsOwner(bytes: ByteArray) {
            val descriptors = instrumentation.uiAutomation.executeShellCommandRw("content write --uri $uri")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { it.write(bytes) }
            ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { it.readBytes() }
        }
        val bytes = instrumentation.context.assets.open("fixture.flac").use { it.readBytes() }
        val external = File(app.cacheDir, "external-change-$id.flac").apply { writeBytes(bytes) }
        val database = "consent-$id.db"
        val store = openMusicStore(app, database)
        val backups = File(app.filesDir, "consent-$id")
        try {
            writeAsOwner(bytes)
            shell("content update --uri $uri --bind is_pending:i:0")
            val audio = AudioFileIO.read(external)
            audio.tag.setField(FieldKey.TITLE, "Changed in another app")
            audio.commit()
            val changed = external.readBytes()
            val library = MusicLibrary(store)
            val track = AndroidLocalSources(app, library).scan(uri.toString()).single()
            val requested = CompletableDeferred<Unit>()
            val edits = AndroidFileEdits(app, store, library, backups.path, requestWriteAccess = { location ->
                writeAsOwner(changed)
                requested.complete(Unit)
                app.mediaWriteRequests.request(location)
            })
            ActivityScenario.launch(MainActivity::class.java).use {
                val editing = async(Dispatchers.Default) { runCatching { edits.editTags(track.id, track.metadata.copy(title = "Manida edit")) } }
                try {
                    withTimeout(5_000) { requested.await() }
                    withTimeout(5_000) {
                        fun allow(node: AccessibilityNodeInfo?): Boolean {
                            if (node == null) return false
                            if (node.text?.toString() == "Allow" && node.isClickable)
                                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            return (0 until node.childCount).any { allow(node.getChild(it)) }
                        }
                        while (!allow(instrumentation.uiAutomation.rootInActiveWindow)) delay(50)
                    }
                    val result = withTimeout(10_000) { editing.await() }
                    assertTrue("An external file change must stop the pending edit", result.isFailure)
                    assertTrue("The external change must remain intact", requireNotNull(app.contentResolver.openInputStream(uri)).use {
                        it.readBytes().contentEquals(changed)
                    })
                } finally { editing.cancelAndJoin() }
            }
        } finally {
            shell("content delete --uri $uri")
            shell("rmdir /sdcard/Music/Manida-consent-$id")
            store.close(); app.deleteDatabase(database); backups.deleteRecursively(); external.delete()
        }
        Unit
    }
}
