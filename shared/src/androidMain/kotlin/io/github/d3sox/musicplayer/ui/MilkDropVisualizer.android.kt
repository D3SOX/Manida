package io.github.d3sox.musicplayer.ui

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.d3sox.musicplayer.visualizer.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Composable
internal actual fun MilkDropVisualizer(close: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var library by remember { mutableStateOf<MilkDropLibrary?>(null) }
    val presets = library?.presets.orEmpty()
    val choices = remember(library) { presets.map { it.info } }
    var selected by remember { mutableIntStateOf(0) }
    var automatic by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { library = withContext(Dispatchers.IO) { milkDropLibrary(File(context.filesDir, "milkdrop")) } }
        catch (failure: Exception) { error = failure.message ?: "Could not open the presets" }
    }
    val surface = remember(context, library) {
        library?.let { MilkDropSurface(context, it, { selected = it }, { error = it }) }
    }
    DisposableEffect(surface, lifecycle) {
        var disposed = false
        fun resume() {
            if (!disposed) surface?.resumeRendering()
        }
        fun pause() { surface?.pauseRendering() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> resume(); Lifecycle.Event.ON_PAUSE -> pause(); else -> Unit }
        }
        lifecycle.lifecycle.addObserver(observer)
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        else surface?.pauseRendering()
        onDispose {
            disposed = true
            lifecycle.lifecycle.removeObserver(observer)
            surface?.release()
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = close) { Text("Close visualizer") }
                    TextButton(onClick = { automatic = !automatic; surface?.renderer?.automatic?.set(automatic) }) {
                        Text(if (automatic) "Lock preset" else "Unlock preset")
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    surface?.let { view -> AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()
                        .semantics { contentDescription = "MilkDrop visualization" }) }
                    if (presets.isEmpty() && error == null) Column(Modifier.align(androidx.compose.ui.Alignment.Center),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text("Preparing the MilkDrop library…")
                    }
                    error?.let { message -> Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    } }
                }
                MilkDropControls(choices, selected, { selected = it; surface?.renderer?.selected?.set(it) },
                    automatic, { automatic = it; surface?.renderer?.automatic?.set(it) })
            }
        }
    }
}

class MilkDropSurface(context: Context, library: MilkDropLibrary, selected: (Int) -> Unit, error: (String?) -> Unit) : GLSurfaceView(context) {
    private var subscription: AutoCloseable? = null
    val renderer = MilkDropRenderer(library,
        { ContextCompat.getMainExecutor(context).execute { selected(it) } },
        { ContextCompat.getMainExecutor(context).execute { error(it) } })
    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = false
        setRenderer(renderer)
    }
    fun release() { renderer.stopRendering(); pauseRendering() }
    fun resumeRendering() {
        if (subscription == null) subscription = VisualizerPcm.subscribe()
        renderer.resumeRendering(); onResume()
    }
    fun pauseRendering() {
        // Detachment can precede Compose's disposal callback. Stop tapping audio before waiting for GL.
        subscription?.close(); subscription = null
        renderer.pauseRendering(); queueEvent { renderer.release() }; onPause()
    }
    override fun onDetachedFromWindow() {
        pauseRendering()
        super.onDetachedFromWindow()
    }
}

class MilkDropRenderer(private val library: MilkDropLibrary, private val changed: (Int) -> Unit, private val error: (String?) -> Unit) : GLSurfaceView.Renderer {
    private val presets = library.presets
    val selected = AtomicInteger(0)
    val automatic = AtomicBoolean(true)
    private var handle = 0L
    private var width = 1
    private var height = 1
    private var loaded = -1
    private var failed = false
    private val closed = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private var changedAt = 0L
    private var drawnAt = 0L
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        release()
        failed = false
        if (closed.get()) return
        try { ProjectMNative.initialize() }
        catch (_: LinkageError) { failed = true; error("Could not load MilkDrop's visualizer library") }
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { this.width = width; this.height = height }
    override fun onDrawFrame(gl: GL10?) {
        if (failed || closed.get() || !active.get()) return
        try {
            val wait = 33_333_333L - (System.nanoTime() - drawnAt)
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            drawnAt = System.nanoTime()
            if (handle == 0L) handle = ProjectMNative.create(width, height, false, library.textures.path)
            if (loaded >= 0 && automatic.get() && System.nanoTime() - changedAt >= 20_000_000_000L)
                selected.updateAndGet { (it + 1) % presets.size }
            val next = selected.get()
            if (loaded != next) {
                try {
                    ProjectMNative.load(handle, presets[next].file.path, loaded >= 0)
                    error(null)
                } catch (failure: IllegalStateException) {
                    error("${presets[next].name} could not be loaded. Choose another preset.")
                }
                loaded = next; changedAt = System.nanoTime(); changed(next)
            }
            ProjectMNative.render(handle, VisualizerPcm.consume(), width, height)
        } catch (failure: Exception) { failed = true; error(failure.message ?: "Could not render MilkDrop") }
    }
    fun release() { if (handle != 0L) ProjectMNative.destroy(handle); handle = 0L; loaded = -1 }
    fun stopRendering() { closed.set(true) }
    fun resumeRendering() { active.set(true) }
    fun pauseRendering() { active.set(false) }
}
