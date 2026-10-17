package me.d3sox.nevumi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.playback.AudioEffects
import me.d3sox.nevumi.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class AndroidProfileColorAndQrUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun colorsAndConfiguredProfilesAreSavedAndCanBeChangedOnTheirSettingsPages() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val controller = app.controller
        val original = runBlocking { MusicPreferences(app.store).load() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) { !controller.state.value.loading }
                assertFalse(LibraryTab.ALBUM_ARTISTS in controller.state.value.preferences.libraryTabs)
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Appearance").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Colors").performClick()
                compose.onNodeWithText("Custom").performClick()
                compose.onNodeWithContentDescription("Custom color: Green").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Custom color: Green").assertIsOn()
                compose.waitUntil(5_000) { controller.state.value.preferences.customColor == 0xFF4CAF50 }
                scenario.recreate()
                compose.waitUntil(5_000) { !controller.state.value.loading }
                assertEquals(ColorMode.CUSTOM, runBlocking { MusicPreferences(app.store).load() }.colors)
                assertEquals(0xFF4CAF50, runBlocking { MusicPreferences(app.store).load() }.customColor)
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Playback").performScrollTo().performClick()
                compose.onNodeWithText("Audio effects").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Enable equalizer").performClick()
                compose.onNodeWithContentDescription("Mono audio").performClick()
                compose.waitUntil(5_000) { controller.state.value.preferences.audioEffects.enabled && controller.state.value.preferences.audioEffects.mono }
                val configured = controller.state.value.preferences.audioEffects
                compose.onNodeWithText("Save as profile").performScrollTo().performClick()
                compose.onNodeWithText("Profile name").performTextInput("My headphones")
                compose.onNodeWithText("Save profile").performClick()
                compose.waitUntil(5_000) { controller.state.value.preferences.audioEffects.profileName == "My headphones" }
                val expected = configured.copy(profileName = "My headphones")
                assertEquals(expected, runBlocking { requireNotNull(controller.equalizerProfiles).saved().single().effects })
                runBlocking { controller.updatePreferences { it.copy(audioEffects = AudioEffects()) }.join() }
                compose.onNodeWithContentDescription("Equalizer profile").performScrollTo().performClick()
                compose.onNodeWithText("My headphones").performClick()
                compose.waitUntil(5_000) { controller.state.value.preferences.audioEffects == expected }
            }
        } finally { runBlocking { controller.updatePreferences { original }.join() } }
    }

    @Test fun invitationsHaveQrCodesAndClosingTheScannerReleasesTheCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val cameras = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        assumeTrue("This device has no test camera", cameras.cameraIdList.isNotEmpty())
        val cameraId = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameras.cameraIdList.first()
        val opened = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(id: String) { if (id == cameraId) opened.set(true) }
            override fun onCameraAvailable(id: String) { if (id == cameraId && opened.get()) released.set(true) }
        }
        val granted = app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        cameras.registerAvailabilityCallback(callback, Handler(Looper.getMainLooper()))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { !app.controller.state.value.loading }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Paired devices").performScrollTo().performClick()
                compose.onNodeWithText("Create invitation").performScrollTo().performClick()
                compose.waitUntil(5_000) { requireNotNull(app.controller.pairedDevices).state.value.invitation != null }
                compose.onNodeWithContentDescription("Pairing QR code").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Cancel invitation").performScrollTo().performClick()
                compose.onNodeWithText("Scan QR code").performScrollTo().performClick()
                compose.waitUntil(10_000) { opened.get() }
                compose.onNodeWithContentDescription("Pairing camera preview").assertIsDisplayed()
                compose.onNodeWithText("Use manual code").performClick()
                compose.waitUntil(10_000) { released.get() }
                compose.onNodeWithText("Pairing invitation").assertExists()
            }
        } finally {
            cameras.unregisterAvailabilityCallback(callback)
            runBlocking { app.controller.pairedDevices?.cancelInvitation() }
            // Orchestrator clears package data and permissions after each test;
            // revoking here would kill the instrumentation process itself.
        }
    }
}
