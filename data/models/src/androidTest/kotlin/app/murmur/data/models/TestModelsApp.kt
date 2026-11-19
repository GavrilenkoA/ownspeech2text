package app.murmur.data.models

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.catalog.Catalog
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.dictation.EnginePreparer
import app.murmur.core.dictation.PrepareReport
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.Outcome
import app.murmur.core.store.InstallState
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * The test APK's Application: real [ModelDownloads] (JobScheduler UIDT, HttpURLConnection, noBackupFilesDir) with a
 * fake [EnginePreparer]. Every download event is also appended to noBackupFilesDir/dl-events.log so that a test (or
 * the kill/resume script) can read what a previous process did.
 */
class TestModelsApp : Application(), ModelJobHost {
    val preparer = TestPreparer()
    val eventLog: File get() = File(noBackupFilesDir, "dl-events.log")

    override val modelDownloads: ModelDownloads by lazy {
        val storage = ModelsModule.storage(this)
        ModelDownloads(
            storage = storage,
            catalog = BundledCatalog,
            scheduler = JobSchedulerDownloads(this),
            http = UrlConnectionTransport(),
            preparer = { preparer },
            freeBytes = ModelsModule.freeBytes(this, storage),
            log = DlLog { name, fields ->
                AndroidDlLog.event(name, *fields)
                synchronized(this) { eventLog.appendText(fields.joinToString(" ", prefix = "${System.currentTimeMillis()} $name ") { "${it.first}=${it.second}" } + "\n") }
            },
        )
    }

    object BundledCatalog : CatalogRepository {
        override val catalog: StateFlow<Catalog> = MutableStateFlow(CatalogParser.bundled())
        override suspend fun addCustom(entry: ModelEntry) = error("not used")
        override suspend fun removeCustom(id: ModelId) = error("not used")
    }

    companion object {
        val app: TestModelsApp get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TestModelsApp
    }
}

/** Records calls, reports progress, and returns [needsCpu] for the artifacts listed there. */
class TestPreparer : EnginePreparer {
    val prepared: MutableList<ArtifactId> = Collections.synchronizedList(mutableListOf())
    val needsCpu: MutableMap<ArtifactId, ArtifactId> = Collections.synchronizedMap(mutableMapOf())

    override suspend fun prepareArtifact(artifact: ArtifactId, onProgress: (Float) -> Unit): Outcome<PrepareReport> {
        prepared += artifact
        for (p in listOf(0.25f, 0.5f, 1f)) { delay(50); onProgress(p) }
        return Outcome.Ok(PrepareReport(artifact, setOf(ComputeUnit.CPU), emptyMap(), needsCpu[artifact]))
    }

    override fun invalidatePrefix() = Unit
}

/**
 * An empty activity: scheduling a user-initiated job requires the app to be visible. Started from the shell with
 * `--es enqueue <artifactId>` it deletes that artifact and queues it afresh (kill_resume.sh: the process then belongs
 * to the app, not to an instrumentation, so that `am kill` can end it).
 */
class VisibleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh start from the shell: a task restored after process death replays the old intent.
        if (savedInstanceState != null) return
        val id = intent.getStringExtra(EXTRA_ENQUEUE)?.let(::ArtifactId) ?: return
        val app = application as TestModelsApp
        CoroutineScope(Dispatchers.Main).launch {
            app.modelDownloads.delete(id)
            app.eventLog.delete()
            app.modelDownloads.enqueue(id, wifiOnly = intent.getBooleanExtra(EXTRA_WIFI_ONLY, true))
        }
    }

    companion object {
        const val EXTRA_ENQUEUE = "enqueue"
        const val EXTRA_WIFI_ONLY = "wifiOnly"

        fun launch(): Activity {
            val inst = InstrumentationRegistry.getInstrumentation()
            val intent = Intent(inst.targetContext, VisibleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return inst.startActivitySync(intent)
        }
    }
}

suspend fun ModelDownloads.awaitState(id: ArtifactId, timeoutMs: Long, pred: (InstallState) -> Boolean): InstallState =
    withTimeout(timeoutMs) { states.states.first { pred(it[id] ?: InstallState.NotInstalled) }[id] ?: InstallState.NotInstalled }
