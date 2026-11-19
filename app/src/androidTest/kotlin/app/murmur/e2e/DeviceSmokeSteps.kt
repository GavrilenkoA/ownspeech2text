package app.murmur.e2e

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.BuildConfig
import app.murmur.core.catalog.ArtifactSelector
import app.murmur.core.dictation.AsrReadiness
import app.murmur.core.dictation.DictationOutcome
import app.murmur.core.dictation.LlmReadiness
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.InstallState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The on-device half of tools/device/murmur_app_smoke.sh (DEVICE_CHECKLIST DC8). Not a test of its own: each run is
 * one step chosen by instrumentation arguments, and it writes `files/dc8/<name>.json`, which the script reads with
 * `run-as`. Without `dc8.step` it is skipped (it only runs from the script).
 *
 *   -e dc8.step facts                       device profile, probes of both processes, installed artifacts
 *   -e dc8.step prepare -e dc8.pack <id>    download a catalog pack (Wi-Fi rule off) and wait for Preparing → Installed
 *   -e dc8.step dictate -e dc8.case <name> -e dc8.seconds <n>
 *        [-e dc8.preIdleSec 70]   wait first and record whether :asr/:llm exited (cold case)
 *        [-e dc8.editPrompt 1]    change "My preferences" right before (prefix rebuild case)
 *        [-e dc8.pinLlm npu]      pin the cleanup model to a unit (forced LLM-on-NPU while :asr holds QNN)
 *        [-e dc8.wav <device path>] play this WAV instead of the microphone (NONINTERACTIVE dry run)
 *        [-e dc8.idleCheckSec 70] after Done, record when :asr/:llm exit
 * The JSON holds codes, numbers and the inserted text (the owner's own scripted phrases); nothing is logged.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeSteps : DictationTest() {
    private fun arg(name: String): String? = E2e.args.getString("dc8.$name")
    private val outDir get() = File(E2e.context.filesDir, "dc8").apply { mkdirs() }

    private fun write(name: String, json: JSONObject) {
        File(outDir, "$name.json").writeText(json.toString(2))
        E2e.note("dc8 wrote $name.json")
    }

    @Test
    fun step() {
        val step = arg("step")
        assumeTrue("DeviceSmokeSteps runs only from tools/device/murmur_app_smoke.sh (-e dc8.step ...)", step != null)
        when (step) {
            "facts" -> facts()
            "prepare" -> prepare(arg("pack") ?: "recommended")
            "dictate" -> dictateCase(checkNotNull(arg("case")) { "dc8.case" }, checkNotNull(arg("seconds")) { "dc8.seconds" }.toLong())
            else -> throw AssertionError("unknown dc8.step $step")
        }
    }

    private fun memAvailableMb(): Long =
        Regex("""MemAvailable:\s+(\d+) kB""").find(E2e.shell("cat /proc/meminfo"))!!.groupValues[1].toLong() / 1024

    private fun facts() = runBlocking {
        val c = E2e.container
        val profile = c.deviceProfile.ensure()
        val asrProbe = c.engines.asrHost.probe()
        val llmProbe = c.engines.llmHost.probe()
        write("facts", JSONObject().apply {
            put("appVersion", BuildConfig.VERSION_NAME)
            put("model", Build.MODEL); put("soc", Build.SOC_MODEL); put("sdk", Build.VERSION.SDK_INT); put("abi", Build.SUPPORTED_ABIS[0])
            put("fingerprint", Build.FINGERPRINT)
            put("profile", JSONObject().apply {
                put("soc", profile.soc ?: JSONObject.NULL)
                put("units", JSONArray(profile.availableUnits.map { it.name }))
                put("runtimes", JSONArray(profile.supportedRuntimes.sorted()))
                put("nativeBuilds", JSONObject(profile.nativeBuilds))
                put("totalRamMb", profile.totalRamMb)
            })
            put("asrProbe", JSONObject().apply { put("units", JSONArray(asrProbe.units.map { it.name })); put("facts", JSONObject(asrProbe.facts)) })
            put("llmProbe", JSONObject().apply { put("units", JSONArray(llmProbe.units.map { it.name })); put("facts", JSONObject(llmProbe.facts)) })
            put("installed", JSONArray(c.installed.states.value.filterValues { it is InstallState.Installed }.keys.map { it.value }.sorted()))
            put("memAvailableMb", memAvailableMb())
        })
    }

    /** One artifact per pack model (ArtifactSelector.choose on this device), downloaded and prepared like the hub does. */
    private fun prepare(packId: String) = runBlocking {
        val c = E2e.container
        val catalog = c.catalog.catalog.value
        val pack = catalog.packs.firstOrNull { it.id == packId } ?: throw AssertionError("no pack $packId in the catalog")
        val profile = c.deviceProfile.ensure()
        val settings = c.settings.settings.value
        val results = JSONArray()
        for (modelId in pack.items) {
            val entry = catalog.model(modelId) ?: throw AssertionError("pack $packId names unknown model ${modelId.value}")
            val pref = settings.unitPreference[modelId.value] ?: UnitPreference.Auto
            val chosen = ArtifactSelector.choose(entry, profile, pref, health = c.unitHealth)
                ?: throw AssertionError("nothing usable for ${modelId.value} on this device")
            val id = chosen.artifact.id
            val t0 = SystemClock.uptimeMillis()
            c.installed.download(id, wifiOnly = false)
            var preparingAt: Long? = null
            val final = withTimeout((arg("timeoutSec")?.toLong() ?: 3_600) * 1_000) {
                c.installed.state(id).first { s ->
                    if (s is InstallState.Preparing && preparingAt == null) preparingAt = SystemClock.uptimeMillis()
                    s is InstallState.Installed || s is InstallState.Failed
                }
            }
            val end = SystemClock.uptimeMillis()
            results.put(JSONObject().apply {
                put("model", modelId.value); put("artifact", id.value); put("unit", chosen.unit.name)
                put("state", final::class.simpleName)
                if (final is InstallState.Failed) put("error", final.error.code)
                put("downloadMs", (preparingAt ?: end) - t0)
                put("preparingMs", preparingAt?.let { end - it } ?: JSONObject.NULL)
                put("health", JSONObject(ComputeUnit.entries.associate { u -> u.name to healthOf(id, u) }))
            })
        }
        write("prepare-$packId", JSONObject().put("pack", packId).put("artifacts", results).put("memAvailableMb", memAvailableMb()))
    }

    private fun healthOf(id: ArtifactId, unit: ComputeUnit): String = when {
        E2e.container.unitHealth.failed(id, unit) -> "failed"
        E2e.container.unitHealth.proven(id, unit) -> "proven"
        else -> "unknown"
    }

    private fun dictateCase(case: String, seconds: Long) {
        val c = E2e.container
        val wav = arg("wav")
        if (wav != null) {
            val bytes = E2e.uiAutomation.executeShellCommand("cat $wav").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() } }
            check(bytes.size > 44) { "dc8.wav $wav is missing" }
            Fixtures.captureFile.writeBytes(bytes)
        } else {
            Fixtures.captureFile.delete()   // the real microphone
        }
        arg("pinLlm")?.let { unit ->
            runBlocking {
                c.settings.update { s ->
                    val llm = (s.cleanup as? CleanupChoice.Model)?.id?.value ?: c.catalog.catalog.value.defaults.llm?.value
                        ?: throw AssertionError("dc8.pinLlm: no cleanup model selected and no catalog default")
                    s.copy(unitPreference = s.unitPreference + (llm to UnitPreference.Pinned(ComputeUnit.valueOf(unit.uppercase()))))
                }
            }
        }
        val out = JSONObject().put("case", case).put("seconds", seconds)
        arg("preIdleSec")?.toLong()?.let { idle ->
            SystemClock.sleep(idle * 1_000)
            out.put("asrRunningBefore", Processes.pidOf(Processes.ASR) != null)
            out.put("llmRunningBefore", Processes.pidOf(Processes.LLM) != null)
        }
        if (arg("editPrompt") == "1") {
            runBlocking { c.settings.update { it.copy(preferences = (it.preferences.orEmpty() + " ").take(1_000)) } }
        }
        TargetApp.open(TargetApp.Target.PLAIN)
        val memBefore = memAvailableMb()
        E2e.note("dc8 speak case=$case seconds=$seconds")
        val pressAt = SystemClock.uptimeMillis()
        dictate(listenMs = seconds * 1_000, listeningTimeoutMs = 30_000) { E2e.note("dc8 listening case=$case") }
        val stopAt = SystemClock.uptimeMillis()
        val done = rec.awaitDone(300_000)
        val doneAt = SystemClock.uptimeMillis()
        E2e.note("dc8 done case=$case")
        val asr = rec.states.firstNotNullOfOrNull { (rec.enginesOf(it.value)?.asr as? AsrReadiness.Ready) }
        val llm = rec.states.firstNotNullOfOrNull { (rec.enginesOf(it.value)?.llm as? LlmReadiness.Ready) }
        val crashes = rec.states.mapNotNull { rec.enginesOf(it.value) }.lastOrNull()
        val row = runBlocking { c.history.entries().first() }.maxByOrNull { it.createdAt }
        out.apply {
            put("outcome", done.outcome::class.simpleName)
            (done.outcome as? DictationOutcome.Failed)?.let { put("error", it.error.code) }
            (done.outcome as? DictationOutcome.Inserted)?.let { put("noCleanup", it.noCleanup); put("skipReason", it.skipReason ?: JSONObject.NULL) }
            put("asr", asr?.let { JSONObject().put("unit", it.info.unit.name).put("deviceName", it.info.deviceName).put("loadMs", it.info.loadMs).put("fellBack", it.fellBack) } ?: JSONObject.NULL)
            put("llm", llm?.let { JSONObject().put("unit", it.info.unit.name).put("deviceName", it.info.deviceName).put("loadMs", it.info.loadMs).put("prefixTokens", it.prefixTokens).put("nCtx", it.nCtx).put("fellBack", it.fellBack) } ?: JSONObject.NULL)
            put("asrCrashes", crashes?.asrCrashes ?: 0); put("llmCrashes", crashes?.llmCrashes ?: 0)
            put("pressToStopMs", stopAt - pressAt); put("stopToDoneMsTest", doneAt - stopAt)
            row?.let { r ->
                put("cleanupOutcome", r.cleanup?.outcome ?: JSONObject.NULL)
                put("insertMethod", r.insertMethod?.name ?: JSONObject.NULL)
                put("timings", JSONObject().put("recordMs", r.timings.recordMs).put("speechMs", r.timings.speechMs)
                    .put("stopToDoneMs", r.timings.stopToDoneMs ?: JSONObject.NULL).put("asrTailMs", r.timings.asrTailMs ?: JSONObject.NULL)
                    .put("llmMs", r.timings.llmMs ?: JSONObject.NULL).put("ocrMs", r.timings.ocrMs ?: JSONObject.NULL))
                put("asrArtifact", r.asr?.artifactId?.value ?: JSONObject.NULL)
                put("llmArtifact", r.cleanup?.model?.artifactId?.value ?: JSONObject.NULL)
            }
            put("insertedText", TargetApp.lastText() ?: JSONObject.NULL)
            put("memAvailableBeforeMb", memBefore); put("memAvailableAtDoneMb", memAvailableMb())
            val failedUnits = JSONArray()
            for (id in c.installed.states.value.keys) for (u in ComputeUnit.entries) if (c.unitHealth.failed(id, u)) failedUnits.put("${id.value}:${u.name}")
            put("unitHealthFailed", failedUnits)
        }
        arg("idleCheckSec")?.toLong()?.let { idle ->
            var asrGone: Long? = null
            var llmGone: Long? = null
            while (SystemClock.uptimeMillis() < doneAt + idle * 1_000) {
                val t = SystemClock.uptimeMillis() - doneAt
                if (asrGone == null && Processes.pidOf(Processes.ASR) == null) asrGone = t
                if (llmGone == null && Processes.pidOf(Processes.LLM) == null) llmGone = t
                SystemClock.sleep(1_000)
            }
            out.put("asrExitAfterDoneMs", asrGone ?: JSONObject.NULL).put("llmExitAfterDoneMs", llmGone ?: JSONObject.NULL)
            out.put("memAvailableAfterIdleMb", memAvailableMb())
        }
        write("dictate-$case", out)
    }
}
