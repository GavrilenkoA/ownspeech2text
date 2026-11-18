package app.murmur.shell

import app.murmur.MainActivity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator test (cover AVD = compact, inner AVD = rail): opens on History, tabs switch screens. */
@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensOnHistoryAndSwitchesTabs() {
        compose.onNodeWithTag("screen-history").assertIsDisplayed()
        compose.onNodeWithTag("tab-models").performClick()
        compose.onNodeWithTag("screen-models").assertIsDisplayed()
    }
}
