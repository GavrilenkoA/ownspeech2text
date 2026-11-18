package app.murmur.testtarget

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One multiline EditText that logs its content ("TGT text=<...>") so that end-to-end tests read the
 * result independently of Murmur (same idea as spikes/platform target). WP-20 adds Compose and WebView fields.
 */
class TargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply { text = "Лёша Козлов"; textSize = 22f }
        val field = EditText(this).apply {
            hint = "Сообщение"
            tag = "target-field"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { Log.i("TGT", "text=$s") }
            })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(title); addView(field)
        })
        field.requestFocus()
    }
}
