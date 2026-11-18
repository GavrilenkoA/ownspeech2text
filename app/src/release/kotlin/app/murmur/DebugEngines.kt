package app.murmur

import android.content.Context
import app.murmur.core.engine.Engines

/** Release builds never use fakes (D6). */
class DebugEngines(@Suppress("unused") private val context: Context) {
    fun fakeEnginesOrNull(): Engines? = null
}
