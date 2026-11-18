package app.murmur.data.db

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.murmur.core.model.MurmurError
import app.murmur.core.store.Settings
import app.murmur.core.store.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [Settings] as one JSON document (ADR-012). Unknown keys are ignored (an old file with the removed
 * `livePreviewModel` key, or a newer app's file, still decodes); an unknown enum value falls back to that field's
 * default; every field is written, so a default that changes in a later release never changes a stored choice.
 */
internal object SettingsSerializer : Serializer<Settings> {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    override val defaultValue: Settings get() = Settings()

    override suspend fun readFrom(input: InputStream): Settings =
        try {
            json.decodeFromString(Settings.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("settings document does not decode", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("settings document does not decode", e)
        }

    override suspend fun writeTo(t: Settings, output: OutputStream) {
        output.write(json.encodeToString(Settings.serializer(), t).encodeToByteArray())
    }
}

/**
 * Typed DataStore settings (DataStore serialises [update] calls: each transform sees the result of the previous one).
 * [settings] is collected eagerly in [scope] (AppContainer.appScope) from construction on. A corrupt document is
 * replaced by the defaults and logged as [MurmurError.StorageFailed] (code only); a read I/O error is retried, then the
 * defaults stay in [settings] and the error is logged the same way.
 */
class DataStoreSettingsRepository internal constructor(
    private val store: DataStore<Settings>,
    scope: CoroutineScope,
    private val log: (code: String, where: String) -> Unit,
) : SettingsRepository {

    /** For AppContainer: one instance per process (DataStore refuses two active stores on one file). */
    constructor(
        context: Context,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(context.dataStoreFile(FILE_NAME), scope)

    internal constructor(
        file: File,
        scope: CoroutineScope,
        log: (code: String, where: String) -> Unit = DbLog::code,
    ) : this(create(file, scope, log), scope, log)

    private val state = MutableStateFlow(Settings())
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) {
            store.data
                .retryWhen { cause, attempt -> (cause is IOException && attempt < READ_RETRIES).also { if (it) delay(RETRY_DELAY_MS) } }
                .catch { e ->
                    if (e !is IOException) throw e
                    log(MurmurError.StorageFailed(null).code, "settings_read")
                    loaded.complete(Unit)
                }
                .collect {
                    state.value = it
                    loaded.complete(Unit)
                }
        }
    }

    override val settings: StateFlow<Settings> = state.asStateFlow()

    override suspend fun current(): Settings {
        loaded.await()
        return state.value
    }

    /**
     * A write that fails with an I/O error is logged and dropped: [settings] keeps showing what is stored, so the screen
     * reflects reality instead of a value that was never saved.
     */
    override suspend fun update(transform: (Settings) -> Settings) {
        try {
            store.updateData { transform(it) }
        } catch (e: IOException) {
            log(MurmurError.StorageFailed(null).code, "settings_write")
        }
    }

    internal companion object {
        const val FILE_NAME = "settings.json"
        private const val READ_RETRIES = 2L
        private const val RETRY_DELAY_MS = 200L

        fun create(file: File, scope: CoroutineScope, log: (String, String) -> Unit): DataStore<Settings> =
            DataStoreFactory.create(
                serializer = SettingsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler {
                    log(MurmurError.StorageFailed(null).code, "settings_corrupt")
                    Settings()
                },
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { file },
            )
    }
}
