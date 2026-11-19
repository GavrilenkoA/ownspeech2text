package app.murmur.core.dictation

import app.murmur.core.context.BuiltInDenylist
import app.murmur.core.model.MurmurError
import app.murmur.testing.FakeInAppTarget
import app.murmur.testing.FakeTargetReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARCHITECTURE 5.2 row 6: the preflight checks, each on its own and in their order (service, mic permission, target
 * snapshot, password field, excluded app, models, storage, thermal). A refused start leaves nothing behind: no history
 * or metrics row, no audio file, the capture discarded, no engine loaded.
 */
class PreflightTest {
    private val telegram = FakeTargetReader.defaultTarget()
    private val passwordField = telegram.copy(field = telegram.field?.copy(isPassword = true))
    /** isPassword unset by the app, but the input type says TYPE_TEXT_VARIATION_PASSWORD (0x81). */
    private val passwordByInputType = telegram.copy(field = telegram.field?.copy(inputType = 0x00000081, isPassword = false))

    private fun Harness.refused(expected: MurmurError) {
        val start = now
        tap(); at(start + 500)
        val done = state as DictationState.Done
        assertEquals(DictationOutcome.Failed(expected, null), done.outcome)
        assertTrue(history().isEmpty())
        assertTrue(w.metrics.snapshot().isEmpty())
        assertTrue(w.audioStore.files.isEmpty())
        assertTrue(w.audio.current?.cancelled ?: true)
        assertTrue(w.engines.asr.loadAttempts.isEmpty())
        assertTrue(w.engines.llm.loadAttempts.isEmpty())
        assertTrue(w.inserter.calls.isEmpty())
        assertNoInternalErrors()
        at(now + 7_000)                                                      // the error pill dismisses
        assertEquals(DictationState.Idle, state)
    }

    private fun Harness.passes() {
        tap(); at(now + 500)
        assertTrue("$state", state is DictationState.Listening)
    }

    @Test fun serviceNotConnected() = runTest {
        val h = harness { permissions.set { it.copy(a11yConnected = false) } }
        h.refused(MurmurError.AccessibilityOff)
        assertTrue(h.w.feedback.haptics.none { it == HapticKind.REJECT })     // no overlay to shake
    }

    @Test fun micPermissionMissing() = runTest {
        val h = harness { permissions.set { it.copy(micGranted = false) } }
        h.refused(MurmurError.MicPermissionMissing)
        assertEquals(HapticKind.REJECT, h.w.feedback.haptics.last())
    }

    @Test fun noTargetSnapshot() = runTest {
        harness { target.target = null }.refused(MurmurError.NoEditableField)
    }

    @Test fun passwordFieldAtTheStart() = runTest {
        harness { target.target = passwordField }.refused(MurmurError.PasswordField)
        harness { target.target = passwordByInputType }.refused(MurmurError.PasswordField)
    }

    @Test fun excludedApps() = runTest {
        harness { target.target = FakeTargetReader.defaultTarget(BuiltInDenylist.packages.first()) }.refused(MurmurError.AppExcluded)
        harness {
            settings.edit { it.copy(hiddenInPackages = setOf(telegram.pkg)) }
        }.refused(MurmurError.AppExcluded)
        harness { target.target = FakeTargetReader.defaultTarget("app.murmur") }.refused(MurmurError.AppExcluded)
    }

    @Test fun lowStorageAtTheThreshold() = runTest {
        val min = DictationConfig().minFreeStorageBytes
        harness { system.freeBytes = min - 1 }.refused(MurmurError.LowStorage)
        harness { system.freeBytes = min }.passes()
    }

    @Test fun thermalCritical() = runTest {
        harness { system.thermalCritical = true }.refused(MurmurError.ThermalCritical)
    }

    /** Every condition at once; fixing them one by one reveals the next check, in the order of row 6. */
    @Test fun checksRunInTheOrderOfRow6() = runTest {
        val h = harness {
            permissions.set { it.copy(a11yConnected = false, micGranted = false) }
            target.target = null
            settings.edit { it.copy(hiddenInPackages = setOf(telegram.pkg)) }
            models.noAsr = true
            system.freeBytes = 0
            system.thermalCritical = true
        }
        val w = h.w
        val order: List<Pair<MurmurError, () -> Unit>> = listOf(
            MurmurError.AccessibilityOff to { w.permissions.set { it.copy(a11yConnected = true) } },
            MurmurError.MicPermissionMissing to { w.permissions.set { it.copy(micGranted = true) } },
            // A snapshot again, but a password field in an app the bubble is hidden in.
            MurmurError.NoEditableField to { w.target.target = passwordField },
            MurmurError.PasswordField to { w.target.target = telegram },
            MurmurError.AppExcluded to { w.settings.edit { it.copy(hiddenInPackages = emptySet()) } },
            MurmurError.NoAsrModel to { w.models.noAsr = false; w.models.noVad = true },
            MurmurError.NoVadModel to { w.models.noVad = false },
            MurmurError.LowStorage to { w.system.freeBytes = 1L shl 40 },
            MurmurError.ThermalCritical to { w.system.thermalCritical = false },
        )
        for ((expected, fix) in order) {
            h.refused(expected)
            fix()
        }
        h.passes()
    }

    /** Row 4: the in-app practice field needs no service and is the only place this app's own package is allowed. */
    @Test fun inAppPracticeSkipsTheServiceAndOwnPackageChecks() = runTest {
        val h = harness { permissions.set { it.copy(a11yConnected = false) } }
        val field = FakeInAppTarget()
        h.at(0); h.o.startInApp(field); h.at(500)
        assertTrue("${h.state}", h.state is DictationState.Listening)
        h.assertNoInternalErrors()
    }
}
