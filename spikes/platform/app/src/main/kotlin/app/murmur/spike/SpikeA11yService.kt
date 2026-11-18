package app.murmur.spike

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * The backbone: bubble (accessibility overlay), mic start on tap, screenshot, context read,
 * insertion ladder, clipboard, :infer lifecycle. Driven by real taps on the bubble and by
 * `adb shell am broadcast -a app.murmur.spike.CMD --es c <cmd> ...` for automation.
 */
class SpikeA11yService : AccessibilityService() {
    companion object {
        const val ACTION_CMD = "app.murmur.spike.CMD"
        @Volatile var instance: SpikeA11yService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = HandlerThread("a11y-io").also { it.start() }
    private val ioHandler = Handler(io.looper)
    private val overlays = HashMap<Int, OverlayHost>()
    private var ime: SpikeIme? = null
    private lateinit var infer: InferClient
    private var recorder: Recorder? = null
    private var dictating = false
    private var tapT0 = 0L

    // Config (cmd "config")
    private var micMode = "fgs"          // fgs | nofgs
    private var insertMethod = "ladder"  // ladder | commit | paste | settext | none
    private var shotDelayMs = 400L
    private var insertText = "Murmur spike text"

    override fun onCreateInputMethod(): InputMethod = SpikeIme(this).also { ime = it }

    override fun onServiceConnected() {
        instance = this
        infer = InferClient(this)
        val si = serviceInfo
        Ev.log("service_connected", "sdk" to android.os.Build.VERSION.SDK_INT, "flags" to si.flags,
            "caps" to si.capabilities, "isAccessibilityTool" to si.isAccessibilityTool,
            "inputMethod" to (inputMethod != null))
        attachOverlay(Display.DEFAULT_DISPLAY)
        registerReceiver(cmd, IntentFilter(ACTION_CMD), Context.RECEIVER_EXPORTED)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
    }

    private fun attachOverlay(displayId: Int) {
        if (overlays.containsKey(displayId)) return
        val h = runCatching { OverlayHost(this, displayId, ::onBubbleTap) }
            .onFailure { Ev.log("overlay_create_failed", "display" to displayId, "err" to it.toString()) }.getOrNull() ?: return
        overlays[displayId] = h; h.attach()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {
            val d = getSystemService(DisplayManager::class.java).getDisplay(id)
            Ev.log("display_added", "id" to id, "name" to d?.name, "flags" to d?.flags)
            if (d != null && (d.flags and Display.FLAG_PRIVATE) == 0) attachOverlay(id)
        }
        override fun onDisplayRemoved(id: Int) { Ev.log("display_removed", "id" to id); overlays.remove(id)?.destroy() }
        override fun onDisplayChanged(id: Int) { overlays[id]?.onDisplayChanged("display_listener") }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Ev.log("config_changed", "orientation" to newConfig.orientation, "sw" to newConfig.smallestScreenWidthDp,
            "w" to newConfig.screenWidthDp, "h" to newConfig.screenHeightDp, "densityDpi" to newConfig.densityDpi)
        overlays.values.forEach { it.onDisplayChanged("config") }
    }

    private var lastPkg: CharSequence? = null
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.packageName != lastPkg) {
            lastPkg = e.packageName
            Ev.log("fg_window_state", "pkg" to e.packageName?.toString(), "cls" to e.className?.toString())
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Ev.log("service_unbind"); cleanup(); return super.onUnbind(intent)
    }

    override fun onDestroy() { Ev.log("service_destroy"); cleanup(); super.onDestroy() }

    private fun cleanup() {
        if (instance == null) return
        instance = null
        infer.unbind("service_destroy") // the binding belongs to this service context
        runCatching { unregisterReceiver(cmd) }
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        overlays.values.forEach { it.destroy() }; overlays.clear()
        scope.cancel(); io.quitSafely()
    }

    // ---------------------------------------------------------------- dictation

    private fun onBubbleTap() { if (!dictating) startDictation() else stopDictation() }

    private fun startDictation() {
        dictating = true
        tapT0 = SystemClock.uptimeMillis()
        overlays.values.forEach { it.state = "listening" }
        val snap = targetWindow()
        Ev.log("dictation_start", "micMode" to micMode, "targetPkg" to snap?.root?.packageName?.toString(),
            "targetWindow" to snap?.id, "imeStarted" to ime?.currentInputStarted,
            "imePkg" to ime?.currentInputEditorInfo?.packageName)
        when (micMode) {
            "fgs" -> try {
                startForegroundService(Intent(this, RecordingFgs::class.java).putExtra("t0", tapT0))
                // recording starts in onFgsStarted()
            } catch (e: Exception) {
                Ev.log("fgs_start_call_failed", "err" to e.toString()); startRecorder("a11y-fallback")
            }
            else -> startRecorder("a11y-nofgs")
        }
        val windowId = snap?.id ?: -1
        main.postDelayed({ screenshot(windowId, "tap") }, shotDelayMs)
        ioHandler.post { Ev.log("context", "data" to contextDump()); Ev.log("ctx2", "data" to ctxV2()) }
    }

    fun onFgsStarted(t0: Long) = main.post { if (dictating && recorder == null) startRecorder("a11y-fgs") }
    fun onFgsFailed(t0: Long, err: String) = main.post { if (dictating && recorder == null) startRecorder("a11y-fgs-failed") }

    private fun startRecorder(label: String) {
        val r = Recorder(label, perSecondLog = true); recorder = r
        val ok = r.start(tapT0)
        main.postDelayed({ Ev.log("rec_first_buffer", "label" to label, "ms" to r.firstBufferMs, "silenced" to r.silencedSeen) }, 1000)
        if (!ok) Ev.log("rec_failed_on_tap", "label" to label)
    }

    private fun stopDictation() {
        dictating = false
        val res = recorder?.stop(); recorder = null
        stopService(Intent(this, RecordingFgs::class.java))
        overlays.values.forEach { it.state = "idle" }
        Ev.log("dictation_stop", "rec" to JSONObject(res ?: emptyMap<String, Any?>()))
        if (insertMethod != "none") scope.launch { insert(insertMethod, insertText) }
    }

    // ---------------------------------------------------------------- windows / context

    /** The app window the user is working in. Not rootInActiveWindow: at tap time the active window may be our bubble. */
    private fun targetWindow(): AccessibilityWindowInfo? {
        val ws = windows
        return ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?: ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    }

    private fun nodeJson(n: AccessibilityNodeInfo?): JSONObject? = n?.let {
        JSONObject().put("cls", it.className).put("pkg", it.packageName).put("editable", it.isEditable)
            .put("password", it.isPassword).put("text", it.text?.toString()).put("hint", it.hintText?.toString())
            .put("showingHint", it.isShowingHintText).put("selStart", it.textSelectionStart).put("selEnd", it.textSelectionEnd)
            .put("inputType", it.inputType).put("viewId", it.viewIdResourceName).put("window", it.windowId)
            .put("dataSensitive", it.isAccessibilityDataSensitive)
            .put("actions", JSONArray(it.actionList.map { a -> a.id }))
    }

    fun contextDump(): JSONObject {
        val t0 = SystemClock.uptimeMillis()
        val o = JSONObject()
        val ws = windows
        o.put("windows", JSONArray(ws.map {
            JSONObject().put("id", it.id).put("type", it.type).put("layer", it.layer).put("focused", it.isFocused)
                .put("active", it.isActive).put("display", it.displayId).put("title", it.title?.toString())
                .put("pkg", it.root?.packageName?.toString())
        }))
        o.put("displaysWithWindows", JSONArray(windowsOnAllDisplays.let { sa -> (0 until sa.size()).map { sa.keyAt(it) } }))
        o.put("rootInActiveWindowPkg", rootInActiveWindow?.packageName?.toString())
        val app = targetWindow()
        o.put("foregroundPkg", app?.root?.packageName?.toString())
        o.put("focusedInput", nodeJson(findFocus(AccessibilityNodeInfo.FOCUS_INPUT)))
        val texts = JSONArray(); var count = 0
        app?.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST)?.let { root ->
            val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
            while (q.isNotEmpty() && count < 2000 && SystemClock.uptimeMillis() - t0 < 150) {
                val n = q.removeFirst(); count++
                val t = n.text?.toString() ?: n.contentDescription?.toString()
                if (!t.isNullOrBlank() && !n.isPassword) texts.put(t)
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
        }
        o.put("texts", texts).put("nodesVisited", count)
        val ei = ime?.currentInputEditorInfo
        o.put("ime", JSONObject().put("started", ime?.currentInputStarted).put("pkg", ei?.packageName)
            .put("inputType", ei?.inputType).put("hint", ei?.hintText?.toString()).put("fieldId", ei?.fieldId))
        o.put("ms", SystemClock.uptimeMillis() - t0)
        return o
    }

    /** CONTEXT_V2 shape: app + field from accessibility, windows/exclusion rects for OCR; no screen text. */
    fun ctxV2(): JSONObject {
        val t0 = SystemClock.uptimeMillis()
        val o = JSONObject()
        val (ws, exclude) = ContextV2.windows(windows, packageName)
        val tWin = SystemClock.uptimeMillis() - t0
        val app = targetWindow()
        val pkg = app?.root?.packageName?.toString()
        val label = pkg?.let { p -> runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrElse { "ERR:" + it.javaClass.simpleName } }
        o.put("app", JSONObject().put("package", pkg).put("label", label).put("windowTitle", app?.title?.toString()))
        val tApp = SystemClock.uptimeMillis() - t0
        // field: node signal + IME (EditorInfo / surrounding text) signal, both recorded
        val node = focusedEditable()
        val ei = ime?.currentInputEditorInfo
        val tNode = SystemClock.uptimeMillis() - t0
        val t1 = SystemClock.uptimeMillis()
        val st = runCatching { ime?.currentInputConnection?.getSurroundingText(1000, 0, 0) }.getOrNull()
        val stMs = SystemClock.uptimeMillis() - t1
        val stBefore = st?.let { s -> val k = (s.selectionStart).coerceIn(0, s.text.length); s.text.subSequence(0, k).toString() }
        val inputType = node?.inputType?.takeIf { it != 0 } ?: ei?.inputType ?: 0
        val password = (node?.isPassword == true) || ContextV2.isPasswordType(inputType) ||
            (ei != null && ContextV2.isPasswordType(ei.inputType))
        if (node != null || ime?.currentInputStarted == true) {
            val f = JSONObject()
                .put("kind", ContextV2.kindOf(inputType, ei?.imeOptions ?: 0))
                .put("hint", node?.hintText?.toString() ?: ei?.hintText?.toString())
                .put("before_cursor", if (password) null else (stBefore ?: node?.let { ContextV2.beforeCursorFromNode(it) }))
                .put("password", password)
            f.put("_probe", JSONObject()
                .put("focusPath", lastFocusPath).put("nodeCls", node?.className).put("nodeEditable", node?.isEditable)
                .put("nodeIsPassword", node?.isPassword).put("nodeInputType", "0x" + Integer.toHexString(node?.inputType ?: 0))
                .put("nodeText", if (password) "<redacted>" else node?.text?.toString()).put("nodeShowingHint", node?.isShowingHintText)
                .put("nodeSel", "${node?.textSelectionStart}-${node?.textSelectionEnd}").put("nodeMultiLine", node?.isMultiLine)
                .put("nodeDataSensitive", node?.isAccessibilityDataSensitive)
                .put("imeStarted", ime?.currentInputStarted).put("eiPkg", ei?.packageName)
                .put("eiInputType", "0x" + Integer.toHexString(ei?.inputType ?: 0)).put("eiHint", ei?.hintText?.toString())
                .put("eiImeOptions", "0x" + Integer.toHexString(ei?.imeOptions ?: 0))
                .put("surroundingBefore", if (password) "<redacted>" else stBefore).put("surroundingMs", stMs)
                .put("nodeBefore", node?.let { if (password) "<redacted>" else ContextV2.beforeCursorFromNode(it) }))
            o.put("field", f)
        }
        o.put("windows", ws).put("exclude_rects", exclude)
        val b = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        o.put("screen_size", JSONArray(listOf(b.width(), b.height())))
        o.put("ms", JSONObject().put("windows", tWin).put("app", tApp).put("node", tNode).put("total", SystemClock.uptimeMillis() - t0))
        return o
    }

    // ---------------------------------------------------------------- screenshots

    /** Capture and close immediately: measures only the API (no bitmap copy). */
    private suspend fun shotLight(kind: String, id: Int): JSONObject = suspendCancellableCoroutine { cont ->
        val t0 = SystemClock.uptimeMillis()
        val cb = object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val o = JSONObject().put("ok", true).put("ms", SystemClock.uptimeMillis() - t0)
                    .put("w", r.hardwareBuffer.width).put("h", r.hardwareBuffer.height)
                r.hardwareBuffer.close(); if (cont.isActive) cont.resume(o)
            }
            override fun onFailure(code: Int) {
                if (cont.isActive) cont.resume(JSONObject().put("ok", false).put("error", code).put("ms", SystemClock.uptimeMillis() - t0))
            }
        }
        if (kind == "window") takeScreenshotOfWindow(id, mainExecutor, cb) else takeScreenshot(id, mainExecutor, cb)
    }

    /** Minimum interval between captures: pairs A,B with B requested `gap` ms after A was requested. */
    private fun shotInterval(gaps: List<Long>) = scope.launch {
        val w = targetWindow()?.id ?: -1
        fun idOf(k: String) = if (k == "window") w else Display.DEFAULT_DISPLAY
        for ((ka, kb) in listOf("display" to "display", "window" to "window", "window" to "display", "display" to "window"))
            for (gap in gaps) {
                delay(1100)
                val tA = SystemClock.uptimeMillis()
                val a = shotLight(ka, idOf(ka))
                val wait = gap - (SystemClock.uptimeMillis() - tA)
                if (wait > 0) delay(wait)
                val actual = SystemClock.uptimeMillis() - tA
                val b = shotLight(kb, idOf(kb))
                Ev.log("shot_interval", "a" to ka, "b" to kb, "gap" to gap, "actualGap" to actual,
                    "aOk" to a.optBoolean("ok"), "aMs" to a.optLong("ms"), "bOk" to b.optBoolean("ok"),
                    "bErr" to b.opt("error"), "bMs" to b.optLong("ms"))
            }
        // Full pipeline cost once: capture -> software bitmap -> scale to 1080 px wide -> discard.
        for (k in listOf("window", "display")) {
            delay(1100)
            val t0 = SystemClock.uptimeMillis()
            val r = shotOnce(k, idOf(k))
            Ev.log("shot_pipeline", "kind" to k, "r" to r, "totalMsInclPng" to SystemClock.uptimeMillis() - t0)
        }
    }

    private suspend fun shotOnce(kind: String, id: Int): JSONObject = suspendCancellableCoroutine { cont ->
        val t0 = SystemClock.uptimeMillis()
        val cb = object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val ms = SystemClock.uptimeMillis() - t0
                val hb = r.hardwareBuffer
                val o = JSONObject().put("kind", kind).put("id", id).put("ok", true).put("ms", ms)
                    .put("w", hb.width).put("h", hb.height)
                val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                if (bmp != null) {
                    // mean luminance of the centre strip: ~0 means the content was blacked out
                    var sum = 0L; val y = bmp.height / 2; var n = 0
                    for (x in 0 until bmp.width step 8) { val p = bmp.getPixel(x, y); sum += ((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3; n++ }
                    o.put("centreLuma", sum / n.coerceAtLeast(1))
                    val f = File(File(filesDir, "shots").also { it.mkdirs() }, "${kind}_${SystemClock.uptimeMillis()}.png")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    o.put("file", f.name); bmp.recycle()
                }
                if (cont.isActive) cont.resume(o)
            }
            override fun onFailure(code: Int) {
                if (cont.isActive) cont.resume(JSONObject().put("kind", kind).put("id", id).put("ok", false)
                    .put("error", code).put("ms", SystemClock.uptimeMillis() - t0))
            }
        }
        if (kind == "window") takeScreenshotOfWindow(id, mainExecutor, cb) else takeScreenshot(id, mainExecutor, cb)
    }

    private fun screenshot(windowId: Int, reason: String) = scope.launch {
        Ev.log("shot", "reason" to reason, "msSinceTap" to SystemClock.uptimeMillis() - tapT0, "r" to shotOnce("window", windowId))
        Ev.log("shot", "reason" to reason, "r" to shotOnce("display", Display.DEFAULT_DISPLAY))
    }

    /** Rate-limit behaviour: back-to-back captures on the same display / window, then after 350 ms. */
    private fun shotSuite() = scope.launch {
        val w = targetWindow()?.id ?: -1
        Ev.log("shot_suite", "step" to "window#1", "r" to shotOnce("window", w))
        Ev.log("shot_suite", "step" to "window#2_immediate", "r" to shotOnce("window", w))
        Ev.log("shot_suite", "step" to "display#1_immediate", "r" to shotOnce("display", Display.DEFAULT_DISPLAY))
        Ev.log("shot_suite", "step" to "display#2_immediate", "r" to shotOnce("display", Display.DEFAULT_DISPLAY))
        delay(350)
        Ev.log("shot_suite", "step" to "display#3_after350", "r" to shotOnce("display", Display.DEFAULT_DISPLAY))
        Ev.log("shot_suite", "step" to "window#3_after350", "r" to shotOnce("window", w))
        Ev.log("shot_suite", "step" to "invalid_window", "r" to shotOnce("window", 987654))
    }

    // ---------------------------------------------------------------- insertion ladder

    /**
     * findFocus(FOCUS_INPUT) alone is not enough: for Compose it returns the AndroidComposeView host
     * (android.view.View, not editable). Fall back to a bounded BFS of the focused app window for a
     * node that is both focused and editable.
     */
    var lastFocusPath = ""
    private fun focusedEditable(): AccessibilityNodeInfo? {
        val f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && f.isEditable) { lastFocusPath = "findFocus"; return f }
        val root = targetWindow()?.root ?: return null.also { lastFocusPath = "none" }
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root); var n = 0
        while (q.isNotEmpty() && n++ < 500) {
            val c = q.removeFirst()
            if (c.isEditable && c.isFocused) { lastFocusPath = "bfs(findFocus=${f?.className})"; return c }
            for (i in 0 until c.childCount) c.getChild(i)?.let { q.add(it) }
        }
        lastFocusPath = "none(findFocus=${f?.className})"; return null
    }

    private fun fieldText(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText) "" else n.text?.toString() ?: ""

    private suspend fun readBack(text: String): JSONObject {
        delay(150)
        val o = JSONObject()
        val n = focusedEditable()
        n?.refresh()
        o.put("nodeText", n?.let { fieldText(it) })
        o.put("nodeContains", n?.let { fieldText(it).contains(text) } ?: false)
        val st = ime?.currentInputConnection?.getSurroundingText(200, 200, 0)
        o.put("surrounding", st?.text?.toString())
        o.put("surroundingContains", st?.text?.toString()?.contains(text) ?: false)
        return o
    }

    private fun setClip(text: String): Boolean = runCatching {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Murmur", text)); true
    }.getOrElse { Ev.log("clip_write_failed", "err" to it.toString()); false }

    private suspend fun tryMethod(m: String, text: String): Boolean {
        val t0 = SystemClock.uptimeMillis()
        val r = JSONObject().put("method", m)
        val node = focusedEditable()
        r.put("node", nodeJson(node) ?: "null").put("focusPath", lastFocusPath)
        val called: Boolean = when (m) {
            "commit" -> {
                val ic = ime?.currentInputConnection
                r.put("imeStarted", ime?.currentInputStarted).put("imePkg", ime?.currentInputEditorInfo?.packageName)
                if (ic == null) false else { ic.commitText(text, 1, null); true }
            }
            "paste" -> if (node == null) false else {
                val clipOk = setClip(text)
                r.put("clipOk", clipOk)
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                r.put("performAction", ok); ok
            }
            "settext" -> if (node == null) false else {
                val cur = fieldText(node)
                var s = node.textSelectionStart; var e = node.textSelectionEnd
                if (s < 0 || e < 0 || s > cur.length || e > cur.length) { s = cur.length; e = cur.length }
                val (a, b) = minOf(s, e) to maxOf(s, e)
                val composed = cur.substring(0, a) + text + cur.substring(b)
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, composed) })
                val caret = a + text.length
                val selOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret) })
                r.put("setText", ok).put("setSelection", selOk).put("composed", composed); ok
            }
            else -> false
        }
        r.put("called", called)
        val rb = readBack(text)
        r.put("readBack", rb).put("ms", SystemClock.uptimeMillis() - t0)
        val verified = rb.optBoolean("nodeContains") || rb.optBoolean("surroundingContains")
        r.put("verified", verified)
        Ev.log("insert_try", "r" to r)
        return verified
    }

    private suspend fun insert(method: String, text: String) {
        if (method == "ladder") {
            setClip(text) // brief: always copy
            for (m in listOf("commit", "paste", "settext")) if (tryMethod(m, text)) { Ev.log("insert_result", "method" to m, "ok" to true); return }
            Ev.log("insert_result", "method" to "clipboard_only", "ok" to false)
        } else Ev.log("insert_result", "method" to method, "ok" to tryMethod(method, text))
    }

    // ---------------------------------------------------------------- probes

    private fun withBubbleHidden(block: () -> Unit) {
        overlays.values.forEach { it.detach() }
        main.postDelayed({ block(); main.postDelayed({ overlays.values.forEach { it.attach() } }, 2500) }, 1500)
    }

    private val cmd = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val what = i.getStringExtra("c") ?: return
            Ev.log("cmd", "c" to what)
            when (what) {
                "config" -> {
                    i.getStringExtra("mic")?.let { micMode = it }
                    i.getStringExtra("insert")?.let { insertMethod = it }
                    i.getStringExtra("text")?.let { insertText = it }
                    if (i.hasExtra("shotDelay")) shotDelayMs = i.getStringExtra("shotDelay")!!.toLong()
                    Ev.log("config", "mic" to micMode, "insert" to insertMethod, "text" to insertText, "shotDelay" to shotDelayMs)
                }
                "where" -> overlays.forEach { (d, h) -> val (x, y) = h.centre(); Ev.log("bubble_where", "display" to d, "x" to x, "y" to y, "lpx" to h.lp.x, "lpy" to h.lp.y) }
                "tap" -> onBubbleTap()
                "ctx" -> ioHandler.post { Ev.log("context", "data" to contextDump()) }
                "ctx2" -> ioHandler.post { Ev.log("ctx2", "data" to ctxV2()) }
                "shot_interval" -> shotInterval((i.getStringExtra("gaps") ?: "0,100,200,300,320,330,340,350,400,500")
                    .split(",").map { it.trim().toLong() })
                "shot_suite" -> shotSuite()
                "insert" -> scope.launch { insert(i.getStringExtra("method") ?: "ladder", i.getStringExtra("text") ?: insertText) }
                "clip" -> {
                    val ok = setClip(i.getStringExtra("text") ?: insertText)
                    val read = runCatching { getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
                        .getOrElse { "err:$it" }
                    Ev.log("clip", "writeOk" to ok, "readBackFromService" to read)
                }
                "hide" -> overlays.values.forEach { it.setHidden(true) }
                "show" -> overlays.values.forEach { it.setHidden(false) }
                "detach" -> overlays.values.forEach { it.detach() }
                "attach" -> overlays.values.forEach { it.attach() }
                // FGS start with NO visible overlay window (relies only on the a11y binding).
                "fgs_probe_hidden" -> withBubbleHidden {
                    tapT0 = SystemClock.uptimeMillis(); dictating = true; micMode = "fgs"
                    startForegroundService(Intent(this@SpikeA11yService, RecordingFgs::class.java).putExtra("t0", tapT0))
                    main.postDelayed({ stopDictation() }, 2000)
                }
                // Record with no FGS and no visible overlay.
                "rec_probe_hidden" -> withBubbleHidden {
                    tapT0 = SystemClock.uptimeMillis(); dictating = true; micMode = "nofgs"; insertMethod = "none"
                    startRecorder("a11y-hidden-nofgs")
                    main.postDelayed({ stopDictation() }, 2000)
                }
                "uidt_bg" -> Ev.log("uidt_schedule", "from" to "a11y_service_background", "result" to
                    DownloadJob.schedule(this@SpikeA11yService, MainActivity.DEFAULT_URL, "bg-test.bin", null, 1_000_000))
                "infer_load" -> infer.request(InferService.MSG_LOAD, i.getStringExtra("mb")?.toInt() ?: 256)
                "infer_probe" -> infer.request(InferService.MSG_RECORD_PROBE)
                "infer_crash" -> infer.request(InferService.MSG_CRASH)
                "infer_native_crash" -> infer.request(InferService.MSG_NATIVE_CRASH)
                "infer_idle" -> { infer.idleMs = i.getStringExtra("ms")?.toLong() ?: 10_000; Ev.log("infer_idle_set", "ms" to infer.idleMs) }
                "ping" -> Ev.log("pong", "connected" to (instance != null), "overlays" to overlays.size)
            }
        }
    }
}

/** The accessibility "IME": same lifecycle a keyboard sees, but it does not replace the keyboard. */
class SpikeIme(service: AccessibilityService) : InputMethod(service) {
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Ev.log("ime_start_input", "pkg" to attribute.packageName, "inputType" to attribute.inputType,
            "hint" to attribute.hintText?.toString(), "fieldId" to attribute.fieldId, "restarting" to restarting)
    }
    override fun onFinishInput() { super.onFinishInput(); Ev.log("ime_finish_input") }
    override fun onUpdateSelection(os: Int, oe: Int, ns: Int, ne: Int, cs: Int, ce: Int) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce)
        Ev.log("ime_selection", "old" to "$os-$oe", "new" to "$ns-$ne")
    }
}
