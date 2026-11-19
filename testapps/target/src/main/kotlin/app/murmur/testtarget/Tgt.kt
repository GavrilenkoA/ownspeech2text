package app.murmur.testtarget

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The receiving side of every end-to-end test. Each target logs, on tag `TGT`:
 * - `open=<target>` when its window comes to the front (so a test knows which field the following lines belong to);
 * - `text=<full field text>` after every change of its field (insertion is verified here, not through Murmur).
 * Read with `adb logcat -d -v raw -s TGT:I`. The format is shared with :platform:a11y's tests (WP-02), which parse
 * lines starting with `text=`.
 */
object Tgt {
    const val TAG = "TGT"

    /** Target names, also the values of `open=`; [Targets.ALL] maps them to activities. */
    const val PLAIN = "plain"
    const val MULTILINE = "multiline"
    const val PASSWORD = "password"
    const val COMPOSE = "compose"
    const val WEB_INPUT = "web-input"
    const val WEB_CONTENTEDITABLE = "web-contenteditable"
    const val SECURE = "secure"

    /** Intent extras of every target: initial text and caret position (JoinPolicy cases; WebView takes the text only). */
    const val EXTRA_PREFILL = "prefill"
    const val EXTRA_CURSOR = "cursor"

    /** Solid background: screenshots and OCR of the target are reproducible. */
    const val BACKGROUND = 0xFFF4F1EA.toInt()

    /** The visible context line every target shows (Cyrillic, so the real run's OCR has something to read). */
    const val CONTEXT_LINE = "Лёша Козлов: созвонимся завтра в десять?"

    fun open(target: String) = Log.i(TAG, "open=$target")
    fun text(text: String) = Log.i(TAG, "text=$text")
}

internal fun Activity.prefill(): Pair<String, Int>? {
    val text = intent.getStringExtra(Tgt.EXTRA_PREFILL) ?: return null
    return text to intent.getIntExtra(Tgt.EXTRA_CURSOR, text.length).coerceIn(0, text.length)
}

/** An EditText that logs every change; [kind] decides the input type. */
internal fun Activity.watchedField(kind: String): EditText = EditText(this).apply {
    tag = "target-field"
    hint = if (kind == Tgt.PASSWORD) "Пароль" else "Сообщение"
    inputType = when (kind) {
        Tgt.MULTILINE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        Tgt.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    if (kind == Tgt.MULTILINE) { isSingleLine = false; minLines = 4 }
    prefill()?.let { (text, cursor) -> setText(text); setSelection(cursor) }
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { Tgt.text(s.toString()) }
    })
}

/** Title (conversation name), the context line and [field], on the solid background. */
internal fun Activity.fieldScreen(title: String, field: EditText) {
    window.setBackgroundDrawable(ColorDrawable(Tgt.BACKGROUND))
    setContentView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Tgt.BACKGROUND)
        setPadding(48, 96, 48, 48)
        addView(TextView(this@fieldScreen).apply { text = title; textSize = 22f })
        addView(TextView(this@fieldScreen).apply { text = Tgt.CONTEXT_LINE; textSize = 18f; setPadding(0, 24, 0, 24) })
        addView(field, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    })
    field.requestFocus()
}

/** `adb shell am start -n app.murmur.testtarget/<activity>` for each target name. */
object Targets {
    val ALL: Map<String, Class<out Activity>> = linkedMapOf(
        Tgt.PLAIN to TargetActivity::class.java,
        Tgt.MULTILINE to MultilineActivity::class.java,
        Tgt.PASSWORD to PasswordActivity::class.java,
        Tgt.COMPOSE to ComposeActivity::class.java,
        Tgt.WEB_INPUT to WebActivity::class.java,
        Tgt.WEB_CONTENTEDITABLE to WebActivity::class.java,
        Tgt.SECURE to SecureActivity::class.java,
    )

    /** The `am start` component and extras of [target], e.g. `app.murmur.testtarget/.WebActivity --es kind contenteditable`. */
    fun amStartArgs(target: String): String {
        val cls = checkNotNull(ALL[target]) { "unknown target $target" }.simpleName
        val extra = when (target) {
            Tgt.WEB_INPUT -> " --es ${WebActivity.EXTRA_KIND} ${WebActivity.KIND_INPUT}"
            Tgt.WEB_CONTENTEDITABLE -> " --es ${WebActivity.EXTRA_KIND} ${WebActivity.KIND_CONTENTEDITABLE}"
            else -> ""
        }
        return "app.murmur.testtarget/.$cls$extra"
    }
}
