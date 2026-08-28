package com.example.sankranthi

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sankranthi.ui.theme.SankranthiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test — needs a connected device or emulator.
 * `./gradlew connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreen_showsAppName() {
        composeTestRule.setContent {
            SankranthiTheme { HomeScreen() }
        }

        composeTestRule.onNodeWithText("Sankranthi").assertIsDisplayed()
    }
}
