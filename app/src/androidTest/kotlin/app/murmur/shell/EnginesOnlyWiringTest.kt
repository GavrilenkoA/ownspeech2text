package app.murmur.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.AppContainer
import app.murmur.MurmurApp
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelKind
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkResultData
import app.murmur.testing.FakeEngines
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-089: the debug fake-engines mode (`files/debug-fake-engines` without the fake container) runs the real data side
 * and the real DefaultBenchmarkRunner and resolver over FakeEngines. What the fake engines report (benchmark records,
 * unit health, the probed device profile) must stay out of the real database, the real unit-health file and the real
 * device-profile cache, which a later normal run ranks and picks compute units from. Run with `-e murmurFake off`.
 * A second AppContainer reads the flag (the app's own container read its replacements before the flag existed); a
 * third one, built after the flag is gone, reads the real stores the way the next normal run would. The record carries a
 * fingerprint of this run and the unit is cleared first, so a run of a broken build (which leaks into the real stores)
 * does not fail later runs.
 */
@RunWith(AndroidJUnit4::class)
class EnginesOnlyWiringTest {
    private val context get() = Device.context.applicationContext
    private val flag get() = File(context.filesDir, FAKE_ENGINES)
    private val fakeProfile get() = File(context.noBackupFilesDir, FAKE_PROFILE)
    private val realProfile get() = File(context.noBackupFilesDir, "device-profile.json")

    @After
    fun cleanUp() {
        flag.delete()
        fakeProfile.delete()
    }

    @Test
    fun whatTheFakeEnginesReportNeverReachesTheRealStores() = runBlocking {
        val app = (context as MurmurApp).container!!
        assumeFalse("run with -e murmurFake off", app.fakeContainer)
        assertFalse("the app's own container runs the real engines", app.engines is FakeEngines)
        val artifact = app.catalog.loaded().models.first { it.kind == ModelKind.ASR && it.artifacts.isNotEmpty() }.artifacts.first().id
        val profileBefore = realProfile.takeIf { it.exists() }?.readBytes()
        app.unitHealth.clear(artifact, ComputeUnit.GPU)
        val fingerprint = "$FINGERPRINT/${System.nanoTime()}"

        flag.writeText("")
        val fakes = AppContainer(context)
        assertTrue("the flag switches the second container to fake engines", fakes.engines is FakeEngines)
        val record = BenchmarkRecord(
            artifact, ComputeUnit.NPU, "0.0.0", fingerprint, System.currentTimeMillis(),
            BenchmarkResultData.Asr(1.0, 1, 1, 1, 1, ComputeUnit.NPU.name, emptyList(), true, null),
        )
        fakes.benchmarks.save(record)
        fakes.unitHealth.markFailed(artifact, ComputeUnit.GPU, "engine.load.asr")
        val probed = fakes.deviceProfile.reprobe()
        assertTrue("the fake-engines run sees its own record", fakes.benchmarks.latest(artifact).first().any { it.buildFingerprint == fingerprint })
        assertTrue("and its own unit health", fakes.unitHealth.failed(artifact, ComputeUnit.GPU))

        flag.delete()
        val next = AppContainer(context)
        assertFalse("the next normal container has real engines", next.engines is FakeEngines)
        val stored = next.benchmarks.latest(artifact).first()
        assertFalse("no fake-engines record in the real benchmark store: $stored", stored.any { it.buildFingerprint == fingerprint })
        assertFalse("no fake-engines failure in the real unit health", next.unitHealth.failed(artifact, ComputeUnit.GPU))
        assertEquals(
            "the real device-profile cache is untouched", profileBefore?.toList(), realProfile.takeIf { it.exists() }?.readBytes()?.toList(),
        )
        assertTrue("the probe over the fake hosts is cached in its own file: $probed", fakeProfile.exists())
    }

    private companion object {
        /** Same names as DebugWiring.FAKE_ENGINES / FAKE_ENGINES_PROFILE (src/debug). */
        const val FAKE_ENGINES = "debug-fake-engines"
        const val FAKE_PROFILE = "debug-fake-engines-device-profile.json"
        const val FINGERPRINT = "engines-only-wiring-test"
    }
}
