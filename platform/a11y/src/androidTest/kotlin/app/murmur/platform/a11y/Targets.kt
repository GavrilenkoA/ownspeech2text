package app.murmur.platform.a11y

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * "Other app" targets for the instrumented tests (lifted from spikes/platform/target). They run in the :target process
 * and log every change of their field as `TGT text=<text>`, so insertion is verified by the receiving side.
 * Intent extras: prefill, cursor, hint, multiline, password.
 */
internal object Tgt {
    const val TAG = "TGT"
    /** Solid window background: the screenshot test compares the area under the keyboard with it. */
    const val BACKGROUND = 0xFFF4F1EA.toInt()
    fun log(text: String) = Log.i(TAG, "text=$text")
}

private fun Activity.watched(edit: EditText): EditText = edit.apply {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { Tgt.log(s.toString()) }
    })
}

private fun Activity.column(vararg views: android.view.View) {
    window.setBackgroundDrawable(ColorDrawable(Tgt.BACKGROUND))
    setContentView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Tgt.BACKGROUND)
        setPadding(48, 160, 48, 48)
        addView(TextView(this@column).apply { text = "Invoice 4471 for Anna, due Friday."; textSize = 20f })
        views.forEach { addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    })
}

private fun Activity.plainField(): EditText {
    val edit = watched(EditText(this)).apply { hint = intent.getStringExtra("hint") ?: "Message" }
    if (intent.getBooleanExtra("password", false)) edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    if (intent.getBooleanExtra("multiline", false)) {
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.isSingleLine = false
    }
    intent.getStringExtra("prefill")?.let { p ->
        edit.setText(p); edit.setSelection(intent.getIntExtra("cursor", p.length).coerceIn(0, p.length))
    }
    return edit
}

class PlainTargetActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val edit = plainField()
        column(edit)
        edit.requestFocus()
    }
}

class SecureTargetActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val edit = plainField()
        column(edit)
        edit.requestFocus()
    }
}

/** A plain field (focused) and a password field below it, for the visibility test. */
class FocusPairActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val plain = watched(EditText(this)).apply { hint = "Message"; id = android.R.id.text1 }
        val pw = EditText(this).apply {
            hint = "Password"; id = android.R.id.text2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        column(plain, pw)
        plain.requestFocus()
    }
}

class ComposeTargetActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setBackgroundDrawable(ColorDrawable(Tgt.BACKGROUND))
        setContent {
            val pre = intent.getStringExtra("prefill") ?: ""
            var value by remember {
                mutableStateOf(TextFieldValue(pre, TextRange(intent.getIntExtra("cursor", pre.length).coerceIn(0, pre.length))))
            }
            val focus = remember { FocusRequester() }
            Column(Modifier.padding(24.dp)) {
                Text("Meeting notes for the Q3 roadmap.")
                TextField(
                    value = value,
                    onValueChange = { value = it; Tgt.log(it.text) },
                    placeholder = { Text("Compose field") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }
}

class WebTargetActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(object {
            @JavascriptInterface fun changed(v: String) = Tgt.log(v)
        }, "Tgt")
        val field = when (intent.getStringExtra("kind")) {
            "contenteditable" -> """<div id="f" contenteditable="true" style="min-height:80px;border:1px solid #888;font-size:20px"></div>"""
            else -> """<input id="f" type="text" style="width:100%;font-size:20px" placeholder="Web field">"""
        }
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width"></head>
            <body style="padding:24px"><p>Ticket 88 web form.</p>$field
            <script>const f=document.getElementById('f');
            f.addEventListener('input',()=>Tgt.changed(f.value!==undefined? f.value : f.innerText));
            setTimeout(()=>{f.focus();},300);</script></body></html>"""
        web.loadDataWithBaseURL("https://target.invalid/", html, "text/html", "utf-8", null)
        setContentView(web)
        web.requestFocus()
    }
}
