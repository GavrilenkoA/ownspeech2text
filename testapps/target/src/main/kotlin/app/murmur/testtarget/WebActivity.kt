package app.murmur.testtarget

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * A WebView page with one focused field: `<input>` (default) or a `contenteditable` div (`--es kind contenteditable`).
 * The page reports every `input` event through a JavaScript bridge, so the log shows what the page really holds
 * (accessibility node text is stale on WebView, platform spike e). The page is inline: no network.
 */
class WebActivity : Activity() {
    private lateinit var target: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_INPUT
        target = if (kind == KIND_CONTENTEDITABLE) Tgt.WEB_CONTENTEDITABLE else Tgt.WEB_INPUT
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Bridge(), "Tgt")
        web.loadDataWithBaseURL("https://target.invalid/", page(kind), "text/html", "utf-8", null)
        setContentView(web)
        web.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        Tgt.open(target)
    }

    private fun page(kind: String): String {
        val initial = prefill()?.first.orEmpty()
        val escaped = escape(initial)
        val field = if (kind == KIND_CONTENTEDITABLE) {
            """<div id="f" contenteditable="true" style="min-height:120px;border:1px solid #8a8577;padding:8px">$escaped</div>"""
        } else {
            """<input id="f" type="text" value="$escaped" placeholder="Сообщение" style="width:100%;padding:8px">"""
        }
        val bg = String.format("#%06X", Tgt.BACKGROUND and 0xFFFFFF)
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <style>body{background:$bg;font-size:20px;padding:24px;font-family:sans-serif}</style></head>
            <body><h3>Почта</h3><p>${escape(Tgt.CONTEXT_LINE)}</p>$field
            <script>
              const f = document.getElementById('f');
              const read = () => (f.value !== undefined ? f.value : f.innerText);
              f.addEventListener('input', () => Tgt.changed(read()));
              setTimeout(() => { f.focus(); }, 300);
            </script></body></html>"""
    }

    /** Text and attribute safe (the prefill goes into value="..."). */
    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private class Bridge {
        @JavascriptInterface fun changed(text: String) = Tgt.text(text)
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val KIND_INPUT = "input"
        const val KIND_CONTENTEDITABLE = "contenteditable"
    }
}
