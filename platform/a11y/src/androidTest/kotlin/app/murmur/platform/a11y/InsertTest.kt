package app.murmur.platform.a11y

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.dictation.InsertResult
import app.murmur.core.model.MurmurError
import app.murmur.platform.a11y.A11yTestSupport.OTHER_APP
import app.murmur.platform.a11y.A11yTestSupport.awaitTargetText
import app.murmur.platform.a11y.A11yTestSupport.clearTargetLog
import app.murmur.platform.a11y.A11yTestSupport.enableService
import app.murmur.platform.a11y.A11yTestSupport.launch
import app.murmur.platform.a11y.A11yTestSupport.launchOtherApp
import app.murmur.platform.a11y.A11yTestSupport.note
import app.murmur.platform.a11y.A11yTestSupport.service
import app.murmur.platform.a11y.A11yTestSupport.shell
import app.murmur.platform.a11y.A11yTestSupport.snapshotOf
import app.murmur.platform.a11y.A11yTestSupport.snapshotOfPackage
import app.murmur.platform.a11y.A11yTestSupport.waitFor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** insert(): the ladder lands in EditText, Compose and WebView, verified by the target's own TGT log line. */
@RunWith(AndroidJUnit4::class)
class InsertTest {
    @Before fun setUp() {
        enableService()
        clearTargetLog()
    }

    private fun insertInto(title: String, text: String): InsertResult {
        val snap = snapshotOf(title)
        val t0 = SystemClock.uptimeMillis()
        val r = runBlocking { service().inserter.insert(snap, text) }
        note("insert surface='$title' result=$r ms=${SystemClock.uptimeMillis() - t0}")
        return r
    }

    @Test
    fun editText() {
        launch(PlainTargetActivity::class.java) { putExtra("prefill", "Hi ") }
        val r = insertInto("Plain target", "from Murmur EditText")
        assertTrue(r is InsertResult.Inserted)
        val logged = awaitTargetText("from Murmur EditText")
        note("TGT text=$logged")
        assertEquals("Hi from Murmur EditText", logged)
    }

    @Test
    fun composeTextField() {
        launch(ComposeTargetActivity::class.java)
        val r = insertInto("Compose target", "from Murmur Compose")
        assertTrue(r is InsertResult.Inserted)
        val logged = awaitTargetText("from Murmur Compose")
        note("TGT text=$logged")
        assertEquals("from Murmur Compose", logged)
    }

    @Test
    fun webViewInput() {
        launch(WebTargetActivity::class.java)
        val r = insertInto("Web target", "from Murmur WebView")
        assertTrue(r is InsertResult.Inserted)
        val logged = awaitTargetText("from Murmur WebView")
        note("TGT text=$logged")
        assertEquals("from Murmur WebView", logged)
    }

    @Test
    fun webViewContentEditable() {
        launch(WebTargetActivity::class.java) { putExtra("kind", "contenteditable") }
        val r = insertInto("Web target", "from Murmur contenteditable")
        assertTrue(r is InsertResult.Inserted)
        val logged = awaitTargetText("from Murmur contenteditable")
        note("TGT text=$logged")
        assertEquals("from Murmur contenteditable", logged.replace(' ', ' ').trim())
    }

    @Test
    fun clipboardWriteIsPastableInTheTarget() {
        launch(PlainTargetActivity::class.java)
        val snap = snapshotOf("Plain target")
        assertTrue(service().inserter.copyToClipboard("clip from Murmur"))
        val pasted = runBlocking {
            withContext(service().ioDispatcher) {
                service().nodes.focusedEditable(snap.windowId)!!.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        }
        assertTrue(pasted)
        val logged = awaitTargetText("clip from Murmur")
        note("clipboard TGT text=$logged")
        assertEquals("clip from Murmur", logged)
    }

    @Test
    fun forceStoppedTargetIsNotInsertedInto() {
        launchOtherApp()
        val snap = snapshotOfPackage(OTHER_APP)
        shell("am force-stop $OTHER_APP")
        waitFor("$OTHER_APP gone from the front", 5_000) {
            if (runBlocking { service().targetReader.snapshot() }?.pkg != OTHER_APP) Unit else null
        }
        val r = runBlocking { service().inserter.insert(snap, "must not land") }
        note("insert after force-stop result=$r")
        assertTrue(r == InsertResult.Failed(MurmurError.TargetChanged) || r == InsertResult.Failed(MurmurError.NoEditableField))
    }
}
