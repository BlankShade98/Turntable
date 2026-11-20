package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.TurntablePreferences
import com.example.model.PlaybackTrack
import com.example.model.WidgetThemePreset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `app name matches Turntable`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Turntable", appName)
    }

    @Test
    fun `widget preview renders transport controls and album art`() {
        composeRule.onNodeWithTag("widget_preview_card").assertIsDisplayed()
        composeRule.onNodeWithTag("widget_album_art").assertIsDisplayed()
        composeRule.onNodeWithTag("preview_previous_button").assertIsDisplayed()
        composeRule.onNodeWithTag("preview_play_pause_button").assertIsDisplayed()
        composeRule.onNodeWithTag("preview_next_button").assertIsDisplayed()
        composeRule.onNodeWithTag("pin_widget_button").assertIsDisplayed()
    }

    @Test
    fun `navigation tabs switch to Designer and Connect screens`() {
        composeRule.onNodeWithTag("nav_tab_design").performClick()
        composeRule.onNodeWithTag("theme_preset_vinyl_warm").assertIsDisplayed()
        composeRule.onNodeWithTag("theme_preset_vinyl_warm").performClick()

        composeRule.onNodeWithTag("nav_tab_connect").performClick()
        composeRule.onNodeWithTag("nav_tab_studio").performClick()
        composeRule.onNodeWithTag("widget_preview_card").assertIsDisplayed()
    }

    @Test
    fun `preferences persist playback track and widget style`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = TurntablePreferences(context)
        val track = PlaybackTrack(
            trackId = "track_1",
            title = "Midnight Groove",
            artist = "Studio Quartet",
            album = "Late Sessions",
            isPlaying = true,
        )
        prefs.saveTrack(track)
        val loaded = prefs.getTrack()
        assertEquals("Midnight Groove", loaded.title)
        assertEquals(true, loaded.isPlaying)

        val currentStyle = prefs.getStyle()
        prefs.updateWidgetStyle(currentStyle.copy(themePreset = WidgetThemePreset.ALBUM_PASTEL))
        assertEquals(WidgetThemePreset.ALBUM_PASTEL, prefs.getStyle().themePreset)
    }
}
