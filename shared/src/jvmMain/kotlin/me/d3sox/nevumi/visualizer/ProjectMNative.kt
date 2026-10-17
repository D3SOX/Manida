package me.d3sox.nevumi.visualizer

import java.io.File

/** Every handle belongs to its OpenGL thread, including destruction. */
object ProjectMNative {
    @Synchronized fun initialize() {
        val resources = System.getProperty("compose.application.resources.dir")
        if (resources != null) System.load(java.io.File(resources, "native/libnevumi_visualizer.so").absolutePath)
        else System.loadLibrary("nevumi_visualizer")
    }
    external fun create(width: Int, height: Int, desktop: Boolean, textures: String): Long
    external fun load(handle: Long, path: String, smooth: Boolean)
    external fun loadData(handle: Long, preset: ByteArray, smooth: Boolean)
    external fun render(handle: Long, pcm: FloatArray, width: Int, height: Int, animate: Boolean = true): Int
    external fun pixels(handle: Long): ByteArray
    external fun destroy(handle: Long)

    /** Loads a preset file, retrying with MilkDrop's line joining when projectM rejects it. */
    fun loadPreset(handle: Long, file: File, smooth: Boolean) {
        try { load(handle, file.path, smooth) }
        catch (failure: IllegalStateException) {
            // Latin-1 maps every byte to one character, so the retry keeps the file's other bytes unchanged.
            val joined = runCatching { milkDropJoinedCode(file.readText(Charsets.ISO_8859_1)) }.getOrNull() ?: throw failure
            loadData(handle, joined.toByteArray(Charsets.ISO_8859_1), smooth)
        }
    }
}
