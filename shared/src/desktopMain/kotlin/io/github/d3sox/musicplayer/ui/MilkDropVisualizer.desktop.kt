package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.d3sox.musicplayer.visualizer.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Composable
internal actual fun MilkDropVisualizer(close: () -> Unit) {
    var library by remember { mutableStateOf<MilkDropLibrary?>(null) }
    val presets = library?.presets.orEmpty()
    val choices = remember(library) { presets.map { it.info } }
    var selected by remember { mutableIntStateOf(0) }
    var automatic by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dismissed by rememberUpdatedState(close)
    val wanted = remember { AtomicInteger(0) }
    val advance = remember { AtomicBoolean(true) }
    val audio = remember { DesktopVisualizerAudio() }
    val notice by audio.notice.collectAsState()
    LaunchedEffect(Unit) {
        try {
            library = withContext(Dispatchers.IO) {
                ProjectMNative.initialize()
                val base = System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share"
                milkDropLibrary(File(base, "io.github.d3sox.musicplayer/milkdrop"))
            }
        } catch (_: LinkageError) { error = "Could not load MilkDrop. Reinstall Manida's visualizer libraries." }
        catch (failure: Exception) { error = failure.message ?: "Could not open MilkDrop" }
    }
    DisposableEffect(library) {
        val running = AtomicBoolean(true)
        val subscription = if (presets.isNotEmpty()) VisualizerPcm.subscribe() else null
        val thread = if (presets.isNotEmpty()) Thread({
            var handle = 0L
            try {
                handle = ProjectMNative.create(960, 640, true, requireNotNull(library).textures.path)
                var current = -1
                var changedAt = System.nanoTime()
                while (running.get()) {
                    val frameStarted = System.nanoTime()
                    if (advance.get() && System.nanoTime() - changedAt >= 20_000_000_000L) wanted.updateAndGet { (it + 1) % presets.size }
                    val next = wanted.get()
                    if (next != current) {
                        try {
                            ProjectMNative.load(handle, presets[next].file.path, current >= 0)
                            scope.launch { error = null }
                        } catch (failure: IllegalStateException) {
                            scope.launch { error = "${presets[next].name} could not be loaded. Choose another preset." }
                        }
                        current = next; changedAt = System.nanoTime()
                        scope.launch { selected = next }
                    }
                    val commands = ProjectMNative.render(handle, VisualizerPcm.consume(), 960, 640)
                    if (commands and 4 != 0) { scope.launch { dismissed() }; break }
                    if (commands and 1 != 0) wanted.updateAndGet { (it + 1) % presets.size }
                    if (commands and 2 != 0) wanted.updateAndGet { (it - 1 + presets.size) % presets.size }
                    val wait = 33_333_333L - (System.nanoTime() - frameStarted)
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                }
            } catch (_: InterruptedException) { /* Closing the visualizer wakes its render loop. */ }
            catch (failure: Exception) { scope.launch { error = failure.message ?: "Could not render MilkDrop" } }
            finally { if (handle != 0L) ProjectMNative.destroy(handle); subscription?.close() }
        }, "Manida MilkDrop").apply { isDaemon = true; start() } else null
        onDispose {
            running.set(false); thread?.interrupt()
            // Finish GL destruction while the application and its native libraries are still alive.
            thread?.join(2_000)
            if (thread == null) subscription?.close()
        }
    }
    DisposableEffect(audio) { onDispose { audio.close() } }
    AlertDialog(onDismissRequest = close, title = { Text("MilkDrop") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The visualizer opens in a separate window. N and P switch presets, F toggles fullscreen, and Esc closes it.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (presets.isEmpty() && error == null) {
                Text("Preparing the MilkDrop library…")
                CircularProgressIndicator()
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            MilkDropControls(choices, selected, { selected = it; wanted.set(it) }, automatic,
                { automatic = it; advance.set(it) })
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close visualizer") } })
}
