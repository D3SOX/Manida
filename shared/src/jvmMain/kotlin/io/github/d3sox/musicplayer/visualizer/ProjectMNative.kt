package io.github.d3sox.musicplayer.visualizer

/** Every handle belongs to its OpenGL thread, including destruction. */
object ProjectMNative {
    @Synchronized fun initialize() {
        val resources = System.getProperty("compose.application.resources.dir")
        if (resources != null) System.load(java.io.File(resources, "native/libmanida_visualizer.so").absolutePath)
        else System.loadLibrary("manida_visualizer")
    }
    external fun create(width: Int, height: Int, desktop: Boolean, textures: String): Long
    external fun load(handle: Long, path: String, smooth: Boolean)
    external fun render(handle: Long, pcm: FloatArray, width: Int, height: Int): Int
    external fun pixels(handle: Long): ByteArray
    external fun destroy(handle: Long)
}
