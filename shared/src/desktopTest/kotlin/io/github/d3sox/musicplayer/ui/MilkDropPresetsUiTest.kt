package io.github.d3sox.musicplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.github.d3sox.musicplayer.visualizer.MilkDropSettings
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
    @Test fun favoritePresetsCanBeBrowsedAndPlaybackSkipsAllOtherPresets() {
        val presets = (0..7).map { io.github.d3sox.musicplayer.visualizer.MilkDropPresetInfo("Preset $it", "Collection") }
        val selected = mutableIntStateOf(0)
        val settings = mutableStateOf(MilkDropSettings(automatic = false))
        runComposeUiTest {
            setContent { MaterialTheme { MilkDropControls(presets, selected.intValue, { selected.intValue = it },
                settings.value, { update -> settings.value = update(settings.value) }) } }
            onNodeWithContentDescription("Favorite current preset: Preset 0").performClick()
            onNodeWithContentDescription("Next preset").performClick()
            onNodeWithContentDescription("Favorite current preset: Preset 1").performClick()
            onNodeWithContentDescription("Only play favorite presets").performClick()
            onNodeWithContentDescription("Next preset").performClick()
            assertEquals(0, selected.intValue)
            onNodeWithContentDescription("Previous preset").performClick()
            assertEquals(1, selected.intValue)
            // Random only picks another favorite, so with two favorites it alternates between them.
            repeat(3) { onNodeWithContentDescription("Random preset").performClick(); assertEquals(it % 2, selected.intValue) }
            onNodeWithContentDescription("Random preset").performClick()
            assertEquals(1, selected.intValue)
            onNodeWithContentDescription("Choose MilkDrop preset").performClick()
            onNodeWithText("Favorites").performClick()
            onNodeWithText("2 presets").assertIsDisplayed()
            onNodeWithText("Preset 2").assertDoesNotExist()
            onNodeWithText("Close").performClick()
            onNodeWithContentDescription("Choose MilkDrop preset").performClick()
            onNodeWithText("Favorites").assertIsSelected()
            onNodeWithContentDescription("Unfavorite preset: Preset 0").performClick()
            onNodeWithContentDescription("Unfavorite preset: Preset 1").performClick()
            onNodeWithText("0 presets").assertIsDisplayed()
            onNodeWithText("Close").performClick()
            onNodeWithContentDescription("Next preset").assertIsNotEnabled()
        }
    }

    @Test fun thePickerOpensCenteredOnTheCurrentPreset() {
        val presets = (0..199).map { io.github.d3sox.musicplayer.visualizer.MilkDropPresetInfo("Preset $it", "Collection") }
        runComposeUiTest {
            setContent { MaterialTheme { MilkDropControls(presets, 150, {}, MilkDropSettings(automatic = false), {}) } }
            onNodeWithContentDescription("Choose MilkDrop preset").performClick()
            val list = onNode(hasScrollToIndexAction()).getBoundsInRoot()
            val item = onNode(hasText("Preset 150") and hasAnyAncestor(hasScrollToIndexAction())).assertIsDisplayed().getBoundsInRoot()
            assertEquals(((list.top + list.bottom) / 2).value, ((item.top + item.bottom) / 2).value, 2f)
        }
    }

    @Test fun thePickerShowsTheLastPresetWhenItIsCurrent() {
        val presets = (0..199).map { io.github.d3sox.musicplayer.visualizer.MilkDropPresetInfo("Preset $it", "Collection") }
        runComposeUiTest {
            setContent { MaterialTheme { MilkDropControls(presets, 199, {}, MilkDropSettings(automatic = false), {}) } }
            onNodeWithContentDescription("Choose MilkDrop preset").performClick()
            onNode(hasText("Preset 199") and hasAnyAncestor(hasScrollToIndexAction())).assertIsDisplayed()
        }
    }

    @Test fun filteringAScrolledListToFewerPresetsKeepsTheFastScrollbarInRange() {
        val presets = (0..199).map { io.github.d3sox.musicplayer.visualizer.MilkDropPresetInfo("Preset $it", "Collection") }
        val settings = mutableStateOf(MilkDropSettings(automatic = false, favorites = presets.take(20).map { it.id }.toSet()))
        runComposeUiTest {
            setContent { MaterialTheme { MilkDropControls(presets, 0, {}, settings.value, { update -> settings.value = update(settings.value) }) } }
            onNodeWithContentDescription("Choose MilkDrop preset").performClick()
            onNode(hasScrollToIndexAction()).performScrollToIndex(150)
            onNodeWithText("Favorites").performClick()
            onNodeWithText("20 presets").assertIsDisplayed()
            onNodeWithContentDescription("Scroll MilkDrop presets").assertExists()
        }
    }

    @Test fun thousandsOfPresetsCanBeFilteredSearchedAndSelectedWithoutChangingTheirIndex() {
        val root = File(System.getProperty("musicplayer.testRoot"), "shared/build/milkdrop-tests").apply { mkdirs() }
        val directory = Files.createTempDirectory(root.toPath(), "picker").toFile()
        try {
            val presets = runBlocking { milkDropLibrary(directory) }.presets.map { it.info }
            val selected = mutableIntStateOf(0)
            val settings = mutableStateOf(MilkDropSettings(automatic = false))
            runComposeUiTest {
                setContent { MaterialTheme {
                    MilkDropControls(presets, selected.intValue, { selected.intValue = it }, settings.value,
                        { update -> settings.value = update(settings.value) })
                } }
                onNodeWithContentDescription("Choose MilkDrop preset").performClick()
                onNodeWithText("Original MilkDrop").performClick()
                onNodeWithText("552 presets").assertIsDisplayed()
                onNodeWithText("Search presets").performTextInput("POSTCARD FROM FRACTOPIA")
                onNodeWithText("1 presets").assertIsDisplayed()
                onNodeWithText("Flexi + Rovastar - postcard from Fractopia").performClick()
                assertEquals(presets.indexOfFirst { it.name == "Flexi + Rovastar - postcard from Fractopia" }, selected.intValue)
                onNodeWithContentDescription("Choose MilkDrop preset").performClick()
                onNodeWithText("Original MilkDrop").assertIsSelected()
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
