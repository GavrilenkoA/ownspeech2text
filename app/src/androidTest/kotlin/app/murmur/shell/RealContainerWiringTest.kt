package app.murmur.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.MurmurApp
import app.murmur.core.benchmark.BenchProgress
import app.murmur.core.catalog.Candidate
import app.murmur.core.model.ComputeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The debug build without the fake container (`-e murmurFake off`, the build the owner installs on the phone): the
 * :testing benchmark runner must not be wired, because it saves made-up records into the real database that the real
 * resolver ranks compute units from. The real container wires WP-14's DefaultBenchmarkRunner (ADR-087): a run of a model
 * that is not installed fails and stores nothing, and no record ever carries the fake fingerprint.
 */
@RunWith(AndroidJUnit4::class)
class RealContainerWiringTest {
    private val container get() = (Device.context.applicationContext as MurmurApp).container!!

    @Test
    fun noMadeUpBenchmarkRecordsOutsideTheFakeContainer() = runBlocking {
        assumeFalse("run with -e murmurFake off", container.fakeContainer)
        val entry = container.catalog.loaded().models.first { it.artifacts.isNotEmpty() }
        val artifact = entry.artifacts.first()
        val before = container.benchmarks.latest(artifact.id).first()

        val progress = withTimeout(20_000) {
            container.benchmarkRunner.run(entry, listOf(Candidate(artifact, ComputeUnit.CPU, usable = true))).toList()
        }

        assertTrue("the runner reports a failure, got $progress", progress.lastOrNull() is BenchProgress.Failed)
        assertFalse("no Finished with records: $progress", progress.any { it is BenchProgress.Finished })
        val after = container.benchmarks.latest(artifact.id).first()
        assertTrue("nothing new in the real benchmark store: $before -> $after", after == before)
        assertFalse("no fake fingerprint in the real store", after.any { it.buildFingerprint == "fake/fingerprint" })
    }
}
