package app.murmur.spike.target

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.text.Editable
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
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** The "other app" for the insertion ladder. Every text change is logged as
 *  `TGT {"field":..,"text":..}` so insertion is verified from the receiving side too. */
internal fun tgt(field: String, text: String) =
    Log.i("MurmurTarget", "TGT " + JSONObject(mapOf("field" to field, "text" to text)))

private fun Activity.plainLayout(field: String, secure: Boolean): EditText {
    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    val edit = EditText(this).apply {
        hint = "plain EditText ($field)"
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { tgt(field, s.toString()) }
        })
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 160, 48, 48)
        addView(TextView(this@plainLayout).apply {
            text = "Spike target: $field" + if (secure) " (FLAG_SECURE)" else ""
            textSize = 22f
        })
        addView(TextView(this@plainLayout).apply { text = "Visible context line: invoice 4471 for Anna, due Friday." })
        addView(edit, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    setContentView(root)
    // Extras for the CONTEXT_V2 field probe: prefill + caret position, custom hint, password type.
    intent.getStringExtra("hint")?.let { edit.hint = it }
    if (intent.getBooleanExtra("password", false))
        edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    if (intent.getBooleanExtra("multiline", false))
        edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    intent.getStringExtra("prefill")?.let { p ->
        edit.setText(p); edit.setSelection(intent.getIntExtra("cursor", p.length).coerceIn(0, p.length))
    }
    edit.requestFocus()
    return edit
}

class PlainActivity : Activity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); plainLayout("plain", secure = false) }
}

class SecureActivity : Activity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); plainLayout("secure", secure = true) }
}

class ComposeActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent {
            val pre = intent.getStringExtra("prefill") ?: ""
            var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(pre,
                androidx.compose.ui.text.TextRange(intent.getIntExtra("cursor", pre.length).coerceIn(0, pre.length)))) }
            val fr = remember { FocusRequester() }
            Column(Modifier.padding(24.dp)) {
                Text("Spike target: compose TextField")
                Text("Visible context line: meeting notes for the Q3 roadmap.")
                TextField(
                    value = value,
                    onValueChange = { value = it; tgt("compose", it.text) },
                    placeholder = { Text("Compose TextField") },
                    modifier = Modifier.fillMaxWidth().focusRequester(fr),
                )
            }
            LaunchedEffect(Unit) { fr.requestFocus() }
        }
    }
}

class WebActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.addJavascriptInterface(object {
            @JavascriptInterface fun changed(id: String, v: String) = tgt("web-$id", v)
        }, "Tgt")
        val kind = intent.getStringExtra("kind") ?: "input"
        val field = when (kind) {
            "textarea" -> """<textarea id="f" rows="4" style="width:100%;font-size:20px"></textarea>"""
            "contenteditable" -> """<div id="f" contenteditable="true" style="min-height:80px;border:1px solid #888;font-size:20px"></div>"""
            else -> """<input id="f" type="text" style="width:100%;font-size:20px" placeholder="web input">"""
        }
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width"></head>
            <body style="padding:24px"><h3>Spike target: WebView $kind</h3><p>Visible context line: ticket 88 web form.</p>$field
            <script>const f=document.getElementById('f');
            const rep=()=>Tgt.changed('$kind', f.value!==undefined? f.value : f.innerText);
            f.addEventListener('input',rep); setTimeout(()=>{f.focus();},300);</script></body></html>"""
        wv.loadDataWithBaseURL("https://spike.invalid/", html, "text/html", "utf-8", null)
        setContentView(wv)
        wv.requestFocus()
    }
}
