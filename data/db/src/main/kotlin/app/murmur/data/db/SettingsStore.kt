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
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

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
 * replaced by the defaults and logged as [MurmurError.StorageFailed] (code only). A read I/O error is logged the same
 * way and [current] goes on with what [settings] holds (the defaults until a read succeeded); the read is retried with
 * a growing delay for as long as [scope] lives, so the stored document and later updates reach [settings] as soon as the
 * file can be read again. Any other read error is logged and ends the collection without taking the process down.
 */
class DataStoreSettingsRepository internal constructor(
    private val store: DataStore<Settings>,
    scope: CoroutineScope,
    private val log: (code: String, where: String) -> Unit,
    private val retryDelayMs: (attempt: Long) -> Long = ::backoffMs,
) : SettingsRepository {

    /**
     * For AppContainer. Every instance made this way shares the process's one DataStore on filesDir/datastore (DataStore
     * refuses two active stores on one file), so a second instance is harmless; [scope] only bounds this instance's
     * collector.
     */
    constructor(
        context: Context,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(processStore(context), scope, DbLog::code)

    internal constructor(
        file: File,
        scope: CoroutineScope,
        log: (code: String, where: String) -> Unit = DbLog::code,
        retryDelayMs: (attempt: Long) -> Long = ::backoffMs,
    ) : this(create(file, CoroutineScope(scope.coroutineContext + Dispatchers.IO), log), scope, log, retryDelayMs)

    private val state = MutableStateFlow(Settings())
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) {
            try {
                store.data
                    .retryWhen { cause, attempt ->
                        if (cause !is IOException) return@retryWhen false
                        log(MurmurError.StorageFailed(null).code, "settings_read")
                        loaded.complete(Unit)
                        delay(retryDelayMs(attempt))
                        true
                    }
                    .collect {
                        state.value = it
                        loaded.complete(Unit)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(MurmurError.StorageFailed(null).code, "settings_read")
            } finally {
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
        private const val FIRST_RETRY_MS = 250L
        private const val MAX_RETRY_MS = 60_000L

        /** 250 ms, 500 ms, 1 s, … capped at one minute. */
        fun backoffMs(attempt: Long): Long = (FIRST_RETRY_MS shl attempt.coerceAtMost(8).toInt()).coerceAtMost(MAX_RETRY_MS)

        @Volatile private var process: DataStore<Settings>? = null

        /** The process's one store on the settings file; it lives as long as the process, not as any caller's scope. */
        fun processStore(context: Context): DataStore<Settings> =
            process ?: synchronized(this) {
                process ?: create(
                    context.applicationContext.dataStoreFile(FILE_NAME),
                    CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    DbLog::code,
                ).also { process = it }
            }

        fun create(file: File, scope: CoroutineScope, log: (String, String) -> Unit): DataStore<Settings> =
            DataStoreFactory.create(
                serializer = SettingsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler {
                    log(MurmurError.StorageFailed(null).code, "settings_corrupt")
                    Settings()
                },
                scope = scope,
                produceFile = { file },
            )
    }
}
