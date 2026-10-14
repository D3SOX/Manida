package io.github.d3sox.musicplayer.visualizer

import io.github.d3sox.musicplayer.resources.Res
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

data class MilkDropPreset(val info: MilkDropPresetInfo, val file: File) {
    val name: String get() = info.name
}
data class MilkDropLibrary(val presets: List<MilkDropPreset>, val textures: File)

@Serializable
private data class BundledPreset(val path: String, val name: String, val collection: String, val category: String)
private val presetPreparation = Mutex()

/** Unpack bundled assets once. User-supplied files outside .library are left untouched. */
@OptIn(ExperimentalResourceApi::class)
suspend fun milkDropLibrary(directory: File): MilkDropLibrary = presetPreparation.withLock {
    check(directory.isDirectory || directory.mkdirs()) { "Could not open the preset folder" }
    val revision = Res.readBytes("files/visualizer/milkdrop-version.txt").decodeToString().trim()
    val bundled = File(directory, ".library")
    if (File(bundled, ".complete").takeIf { it.isFile }?.readText() != revision) {
        val staging = File(directory, ".unpacking")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare the preset folder" }
        try {
            val bytes = Res.readBytes("files/visualizer/milkdrop.zip")
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(digest == revision) { "The bundled MilkDrop library is damaged" }
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val target = File(staging, entry.name)
                    check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Invalid preset path" }
                    if (!entry.isDirectory) {
                        check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "Could not prepare a preset folder" }
                        target.outputStream().buffered().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            check(File(staging, "catalog.json").isFile && File(staging, "textures").isDirectory) { "Incomplete preset library" }
            File(staging, ".complete").writeText(revision)
            check(!bundled.exists() || bundled.deleteRecursively()) { "Could not replace the bundled presets" }
            check(staging.renameTo(bundled)) { "Could not install the bundled presets" }
        } finally { staging.deleteRecursively() }
    }
    val catalog = Json.decodeFromString<List<BundledPreset>>(File(bundled, "catalog.json").readText())
    val presets = catalog.map { MilkDropPreset(MilkDropPresetInfo(it.name, it.collection, it.category, it.path), File(bundled, it.path)) }
    val custom = directory.walkTopDown().onEnter { it == directory || !it.name.startsWith(".") }
        .filter { it.isFile && it.extension.equals("milk", true) }
        .map { MilkDropPreset(MilkDropPresetInfo(it.nameWithoutExtension, "Custom", it.parentFile.relativeTo(directory).path,
            "custom/${it.relativeTo(directory).path}"), it) }
    MilkDropLibrary((presets + custom).sortedWith(compareBy<MilkDropPreset> {
        when (it.info.collection) { "Original MilkDrop" -> 0; "Cream of the Crop" -> 1; else -> 2 }
    }.thenBy { it.name.lowercase() }),
        File(bundled, "textures"))
}
