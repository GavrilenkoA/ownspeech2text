package app.murmur.testtarget

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/**
 * A single-line EditText in a chat-like window ("Лёша Козлов"), focused with the keyboard up. The default (launcher)
 * target; :platform:a11y's tests start it as `app.murmur.testtarget/.TargetActivity`.
 */
open class TargetActivity : Activity() {
    protected open val target: String = Tgt.PLAIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBeforeContent()
        fieldScreen("Лёша Козлов", watchedField(target))
    }

    protected open fun onBeforeContent() {}

    override fun onResume() {
        super.onResume()
        Tgt.open(target)
    }
}

/** Multi-line, sentence-capitalised field (notes / email body). */
class MultilineActivity : TargetActivity() {
    override val target = Tgt.MULTILINE
}

/** A password field: the bubble must stay hidden and nothing may be inserted, copied or stored (ARCHITECTURE 9.2). */
class PasswordActivity : TargetActivity() {
    override val target = Tgt.PASSWORD
}

/** FLAG_SECURE window: the screenshot fails or is black, so dictation runs without screen context (ARCHITECTURE 9.1). */
class SecureActivity : TargetActivity() {
    override val target = Tgt.SECURE
    override fun onBeforeContent() { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
}
