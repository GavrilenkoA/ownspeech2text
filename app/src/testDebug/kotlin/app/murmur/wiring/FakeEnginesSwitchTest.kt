package app.murmur.wiring

import app.murmur.core.store.Settings
import app.murmur.testing.InMemorySettingsRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings "Use fake engines" <-> `files/debug-fake-engines` (WP-20 wave 3; ADR-089 left the switch without a consumer). */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeEnginesSwitchTest {
    private class Flag(var on: Boolean) {
        val writes = mutableListOf<Boolean>()
        fun write(v: Boolean) { writes += v; on = v }
    }

    @Test
    fun theSwitchShowsTheModeThisProcessRunsInWithoutRewritingTheFlag() = runTest {
        // A test wrote the flag before the app started; the stored switch was off.
        val settings = InMemorySettingsRepository(Settings(debugFakeEngines = false))
        val flag = Flag(on = true)
        val job = backgroundScope.launch { FakeEnginesSwitch(runningOnFakes = true, writeFlag = flag::write).run(settings) }
        runCurrent()
        assertEquals(true, settings.settings.value.debugFakeEngines)
        assertEquals("aligning the switch is not a user's toggle", emptyList<Boolean>(), flag.writes)
        job.cancel()
    }

    @Test
    fun aToggleWritesTheFlagForTheNextStart() = runTest {
        val settings = InMemorySettingsRepository(Settings(debugFakeEngines = false))
        val flag = Flag(on = false)
        backgroundScope.launch { FakeEnginesSwitch(runningOnFakes = false, writeFlag = flag::write).run(settings) }
        runCurrent()
        settings.update { it.copy(debugFakeEngines = true) }
        runCurrent()
        assertEquals(listOf(true), flag.writes)
        settings.update { it.copy(debugFakeEngines = false) }
        runCurrent()
        assertEquals(listOf(true, false), flag.writes)
        // Other settings changes never touch the flag.
        settings.update { it.copy(retentionDays = 30) }
        runCurrent()
        assertEquals(listOf(true, false), flag.writes)
        assertEquals(false, flag.on)
    }

    @Test
    fun turningTheSwitchOffInAFakeProcessRemovesTheFlag() = runTest {
        val settings = InMemorySettingsRepository(Settings(debugFakeEngines = true))
        val flag = Flag(on = true)
        backgroundScope.launch { FakeEnginesSwitch(runningOnFakes = true, writeFlag = flag::write).run(settings) }
        runCurrent()
        settings.update { it.copy(debugFakeEngines = false) }
        runCurrent()
        assertEquals(listOf(false), flag.writes)
        assertEquals(false, flag.on)
    }
}
