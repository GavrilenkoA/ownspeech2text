package app.murmur

import android.content.Context
import app.murmur.core.store.SettingsRepository
import app.murmur.wiring.Replacements
import kotlinx.coroutines.CoroutineScope

/** Release builds never use fakes (D6): everything is the real module, and the debug switches have no effect. */
class DebugWiring(@Suppress("unused") private val context: Context, @Suppress("unused") private val scope: CoroutineScope) {
    fun replacements(): Replacements = Replacements.NONE

    @Suppress("UNUSED_PARAMETER")
    fun start(settings: SettingsRepository, replacements: Replacements) = Unit
}
