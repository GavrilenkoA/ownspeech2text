package app.murmur.data.db

import androidx.datastore.dataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.UnitPreference
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

/** The AppContainer path on a device: DbModule.settings(context, appScope) in filesDir/datastore, collected eagerly. */
@RunWith(AndroidJUnit4::class)
class SettingsDeviceTest {
    private val file get() = appContext.dataStoreFile(DataStoreSettingsRepository.FILE_NAME)

    @After
    fun tearDown() { file.delete() }

    @Test
    fun storedDocumentReachesTheStateFlowWithoutBeingAsked() = runBlocking {
        file.delete()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        DbModule.settings(appContext, scope).update {
            it.copy(retentionDays = 1, unitPreference = mapOf("lfm3-1.7b" to UnitPreference.Pinned(ComputeUnit.GPU)))
        }
        scope.coroutineContext[Job]!!.cancelAndJoin()

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = DbModule.settings(appContext, scope)
        // Nobody collects or calls current(): the eager collector alone loads the document.
        withTimeout(5_000) { while (repo.settings.value.retentionDays != 1) delay(10) }
        assertEquals(UnitPreference.Pinned(ComputeUnit.GPU), repo.settings.value.unitPreference["lfm3-1.7b"])
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
