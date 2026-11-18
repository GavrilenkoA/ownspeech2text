package app.murmur

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsTitleAndNativeMessage() {
        compose.onNodeWithTag("title").assertIsDisplayed()
        compose.onNodeWithTag("native").assertIsDisplayed()
    }

    @Test
    fun jniWorksOnDevice() {
        assertEquals(5, NativeBridge.add(2, 3))
        assertTrue(NativeBridge.hello().startsWith("native ok"))
    }
}
