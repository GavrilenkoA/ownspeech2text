package app.murmur.testtarget

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A Compose material3 TextField (no EditText in the tree: insertion goes through the semantics / IME path). */
class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Tgt.BACKGROUND))
        val initial = prefill()
        setContent {
            var value by remember {
                mutableStateOf(initial?.let { (t, c) -> TextFieldValue(t, TextRange(c)) } ?: TextFieldValue(""))
            }
            val focus = remember { FocusRequester() }
            Column(Modifier.fillMaxSize().background(Color(Tgt.BACKGROUND)).padding(24.dp)) {
                Text("Заметки", fontSize = 22.sp)
                Text(Tgt.CONTEXT_LINE, fontSize = 18.sp, modifier = Modifier.padding(vertical = 12.dp))
                TextField(
                    value = value,
                    onValueChange = { next ->
                        val changed = next.text != value.text
                        value = next
                        if (changed) Tgt.text(next.text)
                    },
                    placeholder = { Text("Сообщение") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }

    override fun onResume() {
        super.onResume()
        Tgt.open(Tgt.COMPOSE)
    }
}
