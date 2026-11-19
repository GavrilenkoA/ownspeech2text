package app.murmur

import android.content.Context
import app.murmur.wiring.Replacements
import kotlinx.coroutines.CoroutineScope

/** Release builds never use fakes (D6): everything is the real module. */
class DebugWiring(@Suppress("unused") private val context: Context, @Suppress("unused") private val scope: CoroutineScope) {
    fun replacements(): Replacements = Replacements.NONE
}
