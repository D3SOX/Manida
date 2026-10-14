package io.github.d3sox.musicplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.d3sox.musicplayer.visualizer.milkDropLibrary
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MilkDropPresetsUiTest {
    @Test fun thousandsOfPresetsCanBeFilteredSearchedAndSelectedWithoutChangingTheirIndex() {
        val root = File(System.getProperty("musicplayer.testRoot"), "shared/build/milkdrop-tests").apply { mkdirs() }
        val directory = Files.createTempDirectory(root.toPath(), "picker").toFile()
        try {
            val presets = runBlocking { milkDropLibrary(directory) }.presets.map { it.info }
            val selected = mutableIntStateOf(0)
            runComposeUiTest {
                setContent { MaterialTheme { MilkDropControls(presets, selected.intValue, { selected.intValue = it }, false, {}) } }
                onNodeWithContentDescription("Choose MilkDrop preset").performClick()
                onNodeWithText("Original MilkDrop").performClick()
                onNodeWithText("552 presets").assertIsDisplayed()
                onNodeWithText("Search presets").performTextInput("POSTCARD FROM FRACTOPIA")
                onNodeWithText("1 presets").assertIsDisplayed()
                onNodeWithText("Flexi + Rovastar - postcard from Fractopia").performClick()
                assertEquals(presets.indexOfFirst { it.name == "Flexi + Rovastar - postcard from Fractopia" }, selected.intValue)
                onNodeWithContentDescription("Choose MilkDrop preset").performClick()
                onNodeWithText("Cream of the Crop").performClick()
                onNodeWithText("9795 presets").assertIsDisplayed()
                onNodeWithText("All categories").performClick()
                onNodeWithText("Waveform").performClick()
                onNodeWithText("1279 presets").assertIsDisplayed()
                onNodeWithText("Search presets").performTextInput("there is no such preset")
                onNodeWithText("No presets match your search.").assertIsDisplayed()
            }
        } finally { directory.deleteRecursively() }
    }
}
