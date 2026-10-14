package io.github.d3sox.musicplayer.visualizer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.sin
import kotlin.test.*

class MilkDropTest {
    private fun testDirectory(prefix: String): File {
        val root = File(System.getProperty("musicplayer.testRoot"), "shared/build/milkdrop-tests").apply { mkdirs() }
        return Files.createTempDirectory(root.toPath(), prefix).toFile()
    }
    @Test fun realProjectMDrawsBothCollectionsAndTheirTexturesAndCanBeReopened() = runBlocking {
        ProjectMNative.initialize()
        val directory = testDirectory("rendering")
        try {
            val library = milkDropLibrary(directory)
            val presets = library.presets
            assertEquals(552, presets.count { it.info.collection == "Original MilkDrop" })
            assertEquals(9795, presets.count { it.info.collection == "Cream of the Crop" })
            assertEquals(67, library.textures.listFiles().orEmpty().size)
            val visible = listOf(presets.first(),
                presets.single { it.name == "Flexi + Rovastar - postcard from Fractopia" },
                presets.single { it.name == "Phat_shifter - Angel" })
            val samples = (visible +
                presets.filter { it.info.collection == "Cream of the Crop" && !it.info.category.startsWith("! Transition") }
                    .groupBy { it.info.category.substringBefore('/') }.values.map { it.first() }).distinct()
            repeat(2) {
                val handle = ProjectMNative.create(320, 240, true, library.textures.path)
                try {
                    for (preset in samples) {
                        ProjectMNative.load(handle, preset.file.path, false)
                        repeat(24) { frame ->
                            val pcm = FloatArray(2048) { sin((it / 2 + frame * 1024) * .12).toFloat() * .7f }
                            assertEquals(0, ProjectMNative.render(handle, pcm, 320, 240))
                            Thread.sleep(10)
                        }
                        val pixels = ProjectMNative.pixels(handle)
                        assertEquals(320 * 240 * 4, pixels.size)
                        val colored = (pixels.indices step 4).count { index ->
                            (pixels[index].toInt() and 255) + (pixels[index + 1].toInt() and 255) + (pixels[index + 2].toInt() and 255) > 30
                        }
                        // Some presets need beats or longer feedback before this steady tone becomes visible.
                        if (preset in visible)
                            assertTrue(colored > 50, "${preset.name} must draw the supplied audio, colored pixels=$colored")
                    }
                    assertFailsWith<IllegalStateException> { ProjectMNative.load(handle, File(directory, "missing.milk").path, false) }
                } finally { ProjectMNative.destroy(handle) }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun theWholeLibraryUnpacksOnceAndPreservesCustomPresetsAndRecoversInterruptedPreparation() = runBlocking {
        val directory = testDirectory("library")
        try {
            val custom = File(directory, "My presets/user.milk").apply { parentFile.mkdirs(); writeText("user's preset") }
            File(directory, ".unpacking/unfinished.milk").apply { parentFile.mkdirs(); writeText("partial") }
            val library = milkDropLibrary(directory)
            assertEquals(10348, library.presets.size)
            assertTrue(library.presets.all { it.file.isFile })
            for (texture in library.textures.listFiles().orEmpty()) {
                javax.imageio.ImageIO.createImageInputStream(texture).use { input ->
                    val reader = javax.imageio.ImageIO.getImageReaders(input).next()
                    try {
                        reader.input = input
                        assertEquals("JPEG", reader.formatName.uppercase(), "Decoder review must cover ${texture.name}")
                        assertTrue(reader.getWidth(0) in 1..4096 && reader.getHeight(0) in 1..4096,
                            "${texture.name} must stay within the reviewed decoder allocation limits")
                    } finally { reader.dispose() }
                }
            }
            assertEquals("user's preset", library.presets.single { it.info.collection == "Custom" }.file.readText())
            assertFalse(File(directory, ".unpacking").exists())
            val marker = File(directory, ".library/.complete")
            assertTrue(marker.setLastModified(1000))
            val again = milkDropLibrary(directory)
            assertEquals(library, again)
            assertEquals(1000, marker.lastModified())
            assertEquals("user's preset", custom.readText())
            marker.delete()
            assertEquals(library, milkDropLibrary(directory))
            assertEquals("user's preset", custom.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun audioBufferIsBoundedAndStopsAfterItsLastVisualizerCloses() {
        VisualizerPcm.subscribe().use {
            val pcm = ByteBuffer.allocate(4800 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(4800) { pcm.putShort(16384) }; pcm.flip()
            VisualizerPcm.write16(pcm, 1, 48000)
            val samples = VisualizerPcm.consume()
            assertEquals(2048, samples.size)
            assertTrue(samples.all { it == .5f })
            assertEquals(0, pcm.position(), "The audio tap must not consume the player's input")
        }
        assertFalse(VisualizerPcm.enabled)
        VisualizerPcm.writeStereo(FloatArray(2048) { .9f })
        assertTrue(VisualizerPcm.consume().isEmpty())
    }

    @Test fun desktopCaptureSelectsOnlyTheCurrentProcessesUncorkedAudio() {
        val inputs = Json.parseToJsonElement("""[
            {"index":10,"sink":1,"properties":{"application.process.id":"123"},"corked":false},
            {"index":20,"sink":1,"properties":{"application.process.id":"456"},"corked":false},
            {"index":30,"sink":1,"properties":{"application.process.id":"123"},"corked":true}
        ]""").jsonArray
        val sinks = Json.parseToJsonElement("""[{"index":1,"monitor_source":"output.monitor"}]""").jsonArray
        assertEquals(OwnedAudioStream(10, "output.monitor"), ownedAudioStream(inputs, sinks, JsonArray(emptyList()), 123))
        assertNull(ownedAudioStream(inputs, sinks, JsonArray(emptyList()), 999))
        val native = Json.parseToJsonElement("""[{"index":40,"sink":1,"client":"7","properties":{},"corked":false}]""").jsonArray
        val clients = Json.parseToJsonElement("""[{"index":7,"properties":{"pipewire.sec.pid":"123"}}]""").jsonArray
        assertEquals(OwnedAudioStream(40, "output.monitor"), ownedAudioStream(native, sinks, clients, 123))
    }
}
