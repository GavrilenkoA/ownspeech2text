package app.murmur.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.Settings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The AppContainer path on a device: filesDir/datastore through the process's one DataStore, collected eagerly. The
 * store outlives every test (it lives as long as the process), so each test writes what it reads and resets at the end.
 */
@RunWith(AndroidJUnit4::class)
class SettingsDeviceTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val crashes = mutableListOf<Throwable>()

    private fun appScope(): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, e -> synchronized(crashes) { crashes += e } }
        return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler).also { scopes += it }
    }

    @After
    fun tearDown() = runBlocking {
        DbModule.settings(appContext, appScope()).update { Settings() }
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test
    fun storedDocumentReachesTheStateFlowWithoutBeingAsked() = runBlocking {
        val first = appScope()
        DbModule.settings(appContext, first).update {
            it.copy(retentionDays = 1, unitPreference = mapOf("lfm3-1.7b" to UnitPreference.Pinned(ComputeUnit.GPU)))
        }
        first.coroutineContext[Job]!!.cancelAndJoin()

        // The first instance's scope is gone; the store is not (it is the process's), so a new instance still works.
        val repo = DbModule.settings(appContext, appScope())
        // Nobody collects or calls current(): the eager collector alone loads the document.
        withTimeout(5_000) { while (repo.settings.value.retentionDays != 1) delay(10) }
        assertEquals(UnitPreference.Pinned(ComputeUnit.GPU), repo.settings.value.unitPreference["lfm3-1.7b"])
        assertEquals(emptyList<Throwable>(), crashes)
    }

    @Test
    fun instancesMadeTheAppContainerWayAndThroughDbModuleShareOneStore() = runBlocking {
        // AppContainer constructs DataStoreSettingsRepository(context) directly; DbModule.settings is the documented
        // wiring. Both alive at once: two DataStores on one file would throw IllegalStateException on the first read.
        val viaModule = DbModule.settings(appContext, appScope())
        val direct = DataStoreSettingsRepository(appContext, appScope())
        val another = DbModule.settings(appContext, appScope())
        assertEquals(Settings().typingWpm, withTimeout(5_000) { direct.current() }.typingWpm)

        direct.update { it.copy(typingWpm = 55) }
        viaModule.update { it.copy(retentionDays = 2) }
        for (repo in listOf(viaModule, direct, another)) {
            withTimeout(5_000) { while (repo.settings.value.let { it.typingWpm != 55 || it.retentionDays != 2 }) delay(10) }
            assertEquals(55, repo.current().typingWpm)
        }
        assertEquals(emptyList<Throwable>(), crashes)
    }
}
