package app.murmur.platform.a11y

import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.murmur.core.context.A11ySnapshot
import app.murmur.core.context.FieldSnapshot
import app.murmur.core.ocr.Rect

/**
 * Window and node reads (synchronous binder calls into the target app). Every method runs on the service's
 * "a11y-io" thread, never on main (ARCHITECTURE 14). Reads app, field and geometry only: screen text never comes from
 * the node tree (CONTEXT_V2).
 */
internal class NodeReader(private val service: MurmurAccessibilityService) {

    /** The current windows with the package of application and accessibility-overlay windows. */
    fun windows(): List<WinInfo> {
        val all = service.windowsOnAllDisplays
        val out = ArrayList<WinInfo>()
        for (i in 0 until all.size()) for (w in all.valueAt(i)) out += info(w)
        return out
    }

    private fun info(w: AccessibilityWindowInfo): WinInfo {
        val b = android.graphics.Rect().also { w.getBoundsInScreen(it) }
        val needsPkg = w.type == AccessibilityWindowInfo.TYPE_APPLICATION || w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        return WinInfo(
            id = w.id, type = w.type, focused = w.isFocused, layer = w.layer, displayId = w.displayId,
            pkg = if (needsPkg) w.root?.packageName?.toString() else null,
            title = w.title?.toString(), bounds = Rect(b.left, b.top, b.right, b.bottom),
        )
    }

    private fun window(id: Int): AccessibilityWindowInfo? {
        val all = service.windowsOnAllDisplays
        for (i in 0 until all.size()) all.valueAt(i).firstOrNull { it.id == id }?.let { return it }
        return null
    }

    /**
     * The focused editable node of [windowId]: findFocus(FOCUS_INPUT), else a bounded breadth-first search for a node
     * that is focused and editable (Compose and WebView: findFocus returns the host view, platform spike 2.3).
     */
    fun focusedEditable(windowId: Int, deadlineMs: Long = SystemClock.uptimeMillis() + BFS_BUDGET_MS): AccessibilityNodeInfo? {
        val f = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && f.windowId == windowId && f.isEditable) return f
        val root = window(windowId)?.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST) ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < BFS_MAX_NODES && SystemClock.uptimeMillis() < deadlineMs) {
            val n = queue.removeFirst()
            if (n.isEditable && n.isFocused) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    fun inputFocusWindowId(): Int? = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.windowId?.takeIf { it >= 0 }

    /** PackageManager label (needs <queries> LAUNCHER, ADR-021); null when the package is not visible (launcher). */
    fun label(pkg: String): String? = runCatching {
        val pm = service.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    @Suppress("DEPRECATION") // getRealSize is the rotation-correct size of any display, including a second one.
    fun displaySize(displayId: Int): Point? =
        service.getSystemService(DisplayManager::class.java).getDisplay(displayId)?.let { d -> Point().also { d.getRealSize(it) } }

    private fun hardKeyboard(): Boolean = service.resources.configuration.let {
        it.keyboard != Configuration.KEYBOARD_NOKEYS && it.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    /** Focus for the bubble's visibility policy (DESIGN 5.2). */
    fun focus(): FocusState {
        val ws = windows()
        val target = WindowChoice.target(ws) ?: return FocusState.NONE
        val pkg = target.pkg ?: return FocusState.NONE.copy(displayId = target.displayId)
        val node = focusedEditable(target.id)
        val ei = service.ime?.editorFor(pkg)
        val inputType = FieldFacts.inputType(ei?.inputType, node?.inputType ?: 0)
        val imeShown = ws.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.displayId == target.displayId }
        return FocusState(
            pkg = pkg,
            displayId = target.displayId,
            editableFocused = FieldFacts.editableFocused(node != null, ei != null, imeShown, hardKeyboard()),
            inputType = inputType,
            isPassword = FieldFacts.isPassword(node?.isPassword == true, inputType, ei?.inputType),
            fieldKey = node?.let { (it.hashCode().toLong() shl 32) or (target.id.toLong() and 0xffffffffL) },
        )
    }

    /** The target snapshot of ARCHITECTURE 9.1 for [target] (a window from [ws]). */
    fun snapshot(target: WinInfo, ws: List<WinInfo>): A11ySnapshot? {
        val pkg = target.pkg ?: return null
        val node = focusedEditable(target.id)
        val ime = service.ime
        val ei = ime?.editorFor(pkg)
        val field = if (node == null && ei == null) null else {
            val inputType = FieldFacts.inputType(ei?.inputType, node?.inputType ?: 0)
            val password = FieldFacts.isPassword(node?.isPassword == true, inputType, ei?.inputType)
            val surrounding = if (password || ei == null) null else
                ime.connectionFor(pkg)?.getSurroundingText(BeforeCursor.MAX_CHARS, 0, 0)
                    ?.let { BeforeCursor.fromSurrounding(it.text, it.selectionStart) }
            val fromNode = if (password || node == null) null else
                BeforeCursor.fromNode(node.text, node.isShowingHintText, node.textSelectionStart)
            val b = node?.let { n -> android.graphics.Rect().also { n.getBoundsInScreen(it) } }
            FieldSnapshot(
                inputType = inputType,
                imeOptions = ei?.imeOptions ?: 0,
                hint = (node?.hintText ?: ei?.hintText)?.toString(),
                beforeCursor = BeforeCursor.resolve(password, surrounding, fromNode),
                boundsInScreen = b?.let { Rect(it.left, it.top, it.right, it.bottom) },
                isPassword = password,
                isMultiline = FieldFacts.isMultiline(node?.isMultiLine == true, inputType),
            )
        }
        val size = displaySize(target.displayId)
        return A11ySnapshot(
            pkg = pkg,
            label = label(pkg) ?: target.title,
            windowTitle = target.title,
            windowId = target.id,
            displayId = target.displayId,
            field = field,
            excludeRects = if (size == null) emptyList() else ExclusionRects.of(ws, service.packageName, target.displayId, size.x, size.y),
            capturedAtMs = System.currentTimeMillis(),
        )
    }

    companion object {
        /** The whole snapshot must stay within 150 ms (ARCHITECTURE 9.1); the tree walk is the variable part. */
        const val BFS_BUDGET_MS = 100L
        const val BFS_MAX_NODES = 500
    }
}
