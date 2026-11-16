package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
    fun `app name matches MOBILE CAD`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("MOBILE CAD", context.getString(R.string.app_name))
    }

    @Test
    fun `home screen renders and navigates to new drawing workspace`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("MOBILE CAD").assertIsDisplayed()
        composeRule.onNodeWithTag("new_drawing_button").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 6000) {
            composeRule.onAllNodesWithTag("cad_drawing_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cad_drawing_canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("command_line_input").assertIsDisplayed()
    }
}
