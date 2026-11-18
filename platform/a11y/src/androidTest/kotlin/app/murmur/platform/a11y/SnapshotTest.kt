package app.murmur.platform.a11y

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.context.FieldKinds
import app.murmur.platform.a11y.A11yTestSupport.enableService
import app.murmur.platform.a11y.A11yTestSupport.launch
import app.murmur.platform.a11y.A11yTestSupport.launchOtherApp
import app.murmur.platform.a11y.A11yTestSupport.note
import app.murmur.platform.a11y.A11yTestSupport.service
import app.murmur.platform.a11y.A11yTestSupport.snapshotOf
import app.murmur.platform.a11y.A11yTestSupport.snapshotOfPackage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** snapshot(): target package, label, field kind/hint, before_cursor (ARCHITECTURE 9.1, WP-02 acceptance). */
@RunWith(AndroidJUnit4::class)
class SnapshotTest {
    @Before fun setUp() { enableService() }

    @Test
    fun otherAppPackageLabelAndHint() {
        launchOtherApp()
        val s = snapshotOfPackage(A11yTestSupport.OTHER_APP)
        note("snapshot other_app pkg=${s.pkg} label=${s.label} hint=${s.field?.hint} window=${s.windowId} display=${s.displayId}")
        assertEquals(A11yTestSupport.OTHER_APP, s.pkg)
        // Label from PackageManager: needs <queries> LAUNCHER (ADR-021); not the window title fallback.
        assertEquals("Murmur test target", s.label)
        val f = s.field!!
        assertEquals("Сообщение", f.hint)
        assertEquals(FieldKinds.TYPE_CLASS_TEXT, f.inputType and FieldKinds.TYPE_MASK_CLASS)
        assertEquals("generic", FieldKinds.ctxKind(f.inputType, f.imeOptions, appCategory = null))
        assertEquals("", f.beforeCursor) // empty field showing its hint: never the hint text
    }

    @Test
    fun multilineCaretAfterLineOne() {
        launch(PlainTargetActivity::class.java) {
            putExtra("multiline", true); putExtra("prefill", "Line one\nLine two"); putExtra("cursor", 8); putExtra("hint", "Notes")
        }
        val s = snapshotOf("Plain target")
        val f = s.field!!
        note("snapshot multiline pkg=${s.pkg} label=${s.label} before_len=${f.beforeCursor?.length} multiline=${f.isMultiline}")
        assertEquals(service().packageName, s.pkg)
        assertEquals("A11y test targets", s.label)
        assertEquals("Line one", f.beforeCursor)
        assertTrue(f.isMultiline)
        assertEquals("Notes", f.hint)
        assertTrue(f.boundsInScreen!!.width > 0)
    }

    @Test
    fun passwordFieldHasNoBeforeCursor() {
        launch(PlainTargetActivity::class.java) { putExtra("password", true); putExtra("prefill", "hunter2") }
        val f = snapshotOf("Plain target").field!!
        note("snapshot password is_password=${f.isPassword} before_null=${f.beforeCursor == null}")
        assertTrue(f.isPassword)
        assertNull(f.beforeCursor)
    }

    @Test
    fun composeAndWebViewFieldsAreFoundThroughTheImeAndBfs() {
        launch(ComposeTargetActivity::class.java) { putExtra("prefill", "Hello Compose"); putExtra("cursor", 5) }
        val c = snapshotOf("Compose target").field!!
        note("snapshot compose before=${c.beforeCursor?.length} input=0x${Integer.toHexString(c.inputType)}")
        assertEquals("Hello", c.beforeCursor)

        launch(WebTargetActivity::class.java)
        val w = snapshotOf("Web target").field!!
        note("snapshot web input=0x${Integer.toHexString(w.inputType)} before=${w.beforeCursor}")
        assertEquals("", w.beforeCursor)
        assertEquals(FieldKinds.TYPE_CLASS_TEXT, w.inputType and FieldKinds.TYPE_MASK_CLASS)
    }

    @Test
    fun snapshotIsWithin150msAndCarriesExclusionRects() {
        launch(PlainTargetActivity::class.java) { putExtra("prefill", "abc") }
        snapshotOf("Plain target")
        SystemClock.sleep(1_500) // let the keyboard animation and the first frames settle: measure the steady state
        val times = (1..10).map {
            val t0 = SystemClock.uptimeMillis()
            val s = runBlocking { service().targetReader.snapshot() }!!
            assertTrue(s.excludeRects.isNotEmpty()) // status bar at least; IME when shown
            SystemClock.uptimeMillis() - t0
        }
        note("snapshot ms=$times")
        assertTrue("snapshot times $times", times.sorted()[times.size / 2] < 150)
    }
}
