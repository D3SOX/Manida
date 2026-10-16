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
import kotlin.math.roundToInt
import kotlin.test.*

class MilkDropTest {
    private fun testDirectory(prefix: String): File {
        val root = File(System.getProperty("musicplayer.testRoot"), "shared/build/milkdrop-tests").apply { mkdirs() }
        return Files.createTempDirectory(root.toPath(), prefix).toFile()
    }

    @Test fun websitePresetsAdvanceOnBeatsWithoutRetriggeringOnSustainedBass() = runBlocking {
        ProjectMNative.initialize()
        val directory = testDirectory("beats")
        try {
            val presets = milkDropLibrary(directory).presets.filter { it.info.collection == "Manida" }
            val detector = presets.first().file.readLines().filter { it.startsWith("per_frame") }
            assertTrue(presets.all { it.file.readLines().filter { line -> line.startsWith("per_frame") } == detector })
            val init = detector.filter { it.startsWith("per_frame_init_") }.joinToString("") { it.substringAfter('=') }
            val frame = detector.filterNot { it.startsWith("per_frame_init_") }.joinToString("") { it.substringAfter('=') }
            val handle = ProjectMNative.create(32, 32, true, directory.path)
            try {
                // Feed deterministic audio envelopes through the actual MilkDrop evaluator and read its shader outputs.
                // This separates detector timing from wall-clock scheduling and GPU speed.
                fun checkEnvelope(fps: Int, bass: String, expectedBeats: (Int) -> Int): List<Int> {
                    val fixture = """
                        MILKDROP_PRESET_VERSION=201
                        PSVERSION_WARP=2
                        PSVERSION_COMP=2
                        [preset00]
                        fWaveAlpha=0
                        per_frame_init_1=$init test_frame=0; last_time=0;
                        per_frame_1=time=test_frame/$fps; bass=$bass; bass_att=1; $frame q4=motion_target*2; test_frame=test_frame+1;
                        warp_1=`shader_body { ret = float3(0,0,0); }
                        comp_1=`shader_body { ret = float3(q1, q2/8, q4/32); }
                    """.trimIndent()
                    ProjectMNative.loadData(handle, fixture.toByteArray(), false)
                    return List(fps * 5) { index ->
                        val pixels = ProjectMNative.pixels(handle)
                        val count = ((pixels[2].toInt() and 255) * 32.0 / 255).roundToInt()
                        assertEquals(expectedBeats(index), count, "fps=$fps frame=$index envelope=$bass")
                        if (index > 0 && expectedBeats(index) > expectedBeats(index - 1))
                            assertTrue((pixels[0].toInt() and 255) >= 250, "The pulse must peak on the detected beat")
                        pixels[1].toInt() and 255
                    }
                }
                for (fps in listOf(30, 60)) {
                    val silent = checkEnvelope(fps, "1") { 0 }
                    assertTrue(silent.all { it == 0 }, "Silence must not advance motion")
                    val sustained = checkEnvelope(fps, "if(above(time,0.99),2.4,1)") { if (it >= fps) 1 else 0 }
                    assertEquals(sustained[fps * 3], sustained.last(), "A held note must not keep advancing motion")
                    for (bpm in listOf(90, 120, 180)) {
                        val period = fps * 60 / bpm
                        checkEnvelope(fps, "if(below(test_frame%$period,2),2.4,1)") { it / period + 1 }
                    }
                    // A second attack 100 ms after a kick is part of the same beat.
                    checkEnvelope(fps, "if(equal(test_frame,$fps)+equal(test_frame,${fps + fps / 10}),2.4,1)") {
                        if (it >= fps) 1 else 0
                    }
                }
            } finally { ProjectMNative.destroy(handle) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun realProjectMDrawsBothCollectionsAndTheirTexturesAndCanBeReopened() = runBlocking {
        ProjectMNative.initialize()
        val directory = testDirectory("rendering")
        try {
            val library = milkDropLibrary(directory)
            val presets = library.presets
            assertEquals(552, presets.count { it.info.collection == "Original MilkDrop" })
            assertEquals(9795, presets.count { it.info.collection == "Cream of the Crop" })
            assertEquals(setOf("Violet hour", "Night bus", "Open water", "Sunroom", "After the rain"),
                presets.filter { it.info.collection == "Manida" }.map { it.name }.toSet())
            assertEquals(67, library.textures.listFiles().orEmpty().size)
            val visible = listOf(presets.first(),
                presets.single { it.name == "Flexi + Rovastar - postcard from Fractopia" },
                presets.single { it.name == "Phat_shifter - Angel" }) +
                presets.filter { it.info.collection == "Manida" }
            val samples = (visible +
                presets.filter { it.info.collection == "Cream of the Crop" && !it.info.category.startsWith("! Transition") }
                    .groupBy { it.info.category.substringBefore('/') }.values.map { it.first() }).distinct()
            repeat(2) {
                val handle = ProjectMNative.create(320, 240, true, library.textures.path)
                try {
                    for (preset in samples) {
                        ProjectMNative.load(handle, preset.file.path, false)
                        var frames = 0
                        fun draw() = repeat(24) {
                            val pcm = FloatArray(2048) { sin((it / 2 + frames * 1024) * .12).toFloat() * .7f }
                            assertEquals(0, ProjectMNative.render(handle, pcm, 320, 240))
                            Thread.sleep(10)
                            frames++
                        }
                        fun countColored(): Int {
                            val pixels = ProjectMNative.pixels(handle)
                            assertEquals(320 * 240 * 4, pixels.size)
                            return (pixels.indices step 4).count { index ->
                                (pixels[index].toInt() and 255) + (pixels[index + 1].toInt() and 255) + (pixels[index + 2].toInt() and 255) >
                                    if (preset.info.collection == "Manida") 180 else 30
                            }
                        }
                        draw()
                        var colored = countColored()
                        // Presets animate by elapsed time, so a slow runner can need more frames before a preset shows.
                        while (preset in visible && colored <= 50 && frames < 240) { draw(); colored = countColored() }
                        // Some presets need beats or longer feedback before this steady tone becomes visible.
                        if (preset in visible)
                            assertTrue(colored > 50, "${preset.name} must draw the supplied audio, colored pixels=$colored")
                        if (preset == visible.first()) {
                            val robot = java.awt.Robot()
                            val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                            fun screen() = robot.createScreenCapture(bounds).getRGB(0, 0, bounds.width, bounds.height, null, 0, bounds.width)
                            val paused = screen()
                            assertTrue(paused.any { it and 0xffffff != 0 }, "The SDL window must be visible before checking its frozen frame")
                            repeat(4) {
                                ProjectMNative.render(handle, FloatArray(2048) { .9f }, 320, 240, animate = false)
                                Thread.sleep(25)
                            }
                            assertContentEquals(paused, screen(), "Paused rendering must keep the previous picture")
                        }
                    }
                    assertFailsWith<IllegalStateException> { ProjectMNative.load(handle, File(directory, "missing.milk").path, false) }
                } finally { ProjectMNative.destroy(handle) }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun presetsThatSplitCodeAcrossLinesLoadAsInMilkDrop() = runBlocking {
        assertEquals("fRating=5\nper_frame_1=a=bass*.34; b=1;\nper_pixel_1=rot=0;", milkDropJoinedCode(
            "fRating=5\nper_frame_1=a=bass*.\r\nper_frame_2=34; // comment\nper_frame_3=b=1;\nper_frame_5=ignored=1;\nper_pixel_1=rot=0;"))
        assertNull(milkDropJoinedCode("per_frame_1=a=1;\nper_pixel_1=b=2;"))
        ProjectMNative.initialize()
        val directory = testDirectory("split-code")
        try {
            val library = milkDropLibrary(directory)
            val handle = ProjectMNative.create(320, 240, true, library.textures.path)
            try {
                for (name in listOf("Idiot24-7-mindbender7(superbendmix1)",
                    "Liquid Glowsticks - ATwisted Pre Set Mix By AdamFX 2 martin - disco mix Ft Hexocollie 27")) {
                    val preset = library.presets.single { it.name == name }
                    assertFailsWith<IllegalStateException> { ProjectMNative.load(handle, preset.file.path, false) }
                    ProjectMNative.loadPreset(handle, preset.file, false)
                    assertEquals(0, ProjectMNative.render(handle, FloatArray(2048), 320, 240))
                }
            } finally { ProjectMNative.destroy(handle) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun theWholeLibraryUnpacksOnceAndPreservesCustomPresetsAndRecoversInterruptedPreparation() = runBlocking {
        val directory = testDirectory("library")
        try {
            val custom = File(directory, "My presets/user.milk").apply { parentFile.mkdirs(); writeText("user's preset") }
            File(directory, ".unpacking/unfinished.milk").apply { parentFile.mkdirs(); writeText("partial") }
            val library = milkDropLibrary(directory)
            assertEquals(10353, library.presets.size)
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
            val imported = library.presets.single { it.info.id == "custom/My presets/user.milk" }
            assertEquals("Manida", imported.info.collection)
            assertEquals("My presets", imported.info.category)
            assertEquals("user's preset", imported.file.readText())
            assertFalse(library.presets.any { it.info.collection == "Custom" })
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
