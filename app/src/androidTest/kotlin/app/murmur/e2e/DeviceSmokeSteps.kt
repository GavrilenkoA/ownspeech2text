package app.murmur.e2e

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.BuildConfig
import app.murmur.core.audio.PcmChunk
import app.murmur.core.audio.PcmFormat
import app.murmur.core.catalog.ArtifactSelector
import app.murmur.core.dictation.AsrReadiness
import app.murmur.core.dictation.DictationOutcome
import app.murmur.core.dictation.DictationState
import app.murmur.core.dictation.LlmReadiness
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.PrefixCacheKey
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.AudioKey
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.core.model.UnitPreference
import app.murmur.core.prompt.PromptEditRules
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.InstallState
import app.murmur.platform.audio.AndroidAudioCapture
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The on-device half of tools/device/murmur_app_smoke.sh (DEVICE_CHECKLIST DC8). Not a test of its own: each run is
 * one step chosen by instrumentation arguments; it writes `files/dc8/<name>.json`, which the script reads with
 * `run-as`. Without `dc8.step` it is skipped (it only runs from the script).
 *
 *   -e dc8.step facts                                  device profile, probes of both processes, installed artifacts
 *   -e dc8.step prepare -e dc8.pack <id>               download a catalog pack (Wi-Fi rule off), wait for Preparing →
 *                       | -e dc8.artifacts <a,b>       Installed; or exactly these artifacts
 *                       [-e dc8.prepareInstalled <a,b>] then run Preparing on these already installed artifacts (the
 *                                                       emulator's pushed models: the LLM builds and stores its prefix
 *                                                       snapshot there, as after a download on the phone)
 *   -e dc8.step dictate -e dc8.cases <case,case,...>   the scripted dictations, all in ONE instrumentation run (its end
 *                                                       kills every app process, which would fake the idle exit)
 *        case = <name>:<seconds>[:idle<N>][:edit][:pin<unit>][:scale<x>][:probe]
 *          idle<N>   wait N s first and record whether :asr/:llm were still running (cold case, "first after 70 s idle")
 *          edit      add (or, if a stopped run left it there, remove) the sentence [EDIT_SENTENCE] in "My preferences"
 *                    right before, and restore the user's text after the case: "right after a prompt edit". The
 *                    sha256 of the cache prefix (render.cache_prefix of the active profile, as Cleaner.prefix renders
 *                    it) is recorded before and after, so the report proves the prefix really changed (a
 *                    whitespace-only edit does not: the renderer strips it)
 *          probe     after this case, while :asr and :llm are warm: (1) three timed AndroidAudioCapture stop() calls
 *                    with keepRecording on (WP-09: < 200 ms while :asr and :llm run) and the stored file's codec;
 *                    (2) the case's own recording (history audio) replayed in real time into a new session of the
 *                    loaded ASR, recording Transcript.unit and how long finish() takes after the last sample (WP-06:
 *                    Parakeet on the NPU, unit NPU, finish within 3 s of EOF)
 *          pin<unit> pin the cleanup model to npu/gpu/cpu ("forced LLM-on-NPU while :asr holds QNN")
 *          scale<x>  Settings.debugTimeoutScale = x for this case only (scale0.1: the forced timeout of WP-05's DC8 note;
 *                    the script then counts the client's "process still alive ... killing" lines)
 *        [-e dc8.wavDir <device dir>]  play <dir>/<name>.wav instead of the microphone (NONINTERACTIVE dry run)
 *        [-e dc8.models <asr>,<llm>]   select these models first (the emulator dry run: GigaAM + Qwen3.5, ADR-063)
 *        [-e dc8.emulator 1]           CPU emulator (also for prepare): LLM pinned to CPU, memory gate ignored,
 *                                      timeouts scaled (debug knobs; x8 as in Pipeline.timeoutScale)
 *        [-e dc8.idleCheckSec 70]      after the last case, record when :asr/:llm exit and MemAvailable afterwards
 *        [-e dc8.cancelCheck 1]        then press, cancel while Arming or while the ASR still loads, and record whether
 *                                      :asr is gone idleUnloadSec + 2 s later (WP-05: a cancelled bind leaves no process)
 * Logcat lines `E2E: dc8 speak case=<name> seconds=<n>` and `dc8 listening case=<name>` let the script show the text
 * to read at the right moment. The JSON holds codes, numbers and the inserted text (the owner's own scripted
 * phrases, kept in the local report only); nothing of it is logged.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeSteps : DictationTest() {
    private fun arg(name: String): String? = E2e.args.getString("dc8.$name")
    override val dictates: Boolean get() = arg("step") == "dictate"
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
            "prepare" -> prepare()
            "dictate" -> dictateAll(checkNotNull(arg("cases")) { "dc8.cases" })
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
            put("asrProbe", JSONObject().put("units", JSONArray(asrProbe.units.map { it.name })).put("facts", JSONObject(asrProbe.facts)))
            put("llmProbe", JSONObject().put("units", JSONArray(llmProbe.units.map { it.name })).put("facts", JSONObject(llmProbe.facts)))
            put("installed", JSONArray(c.installed.states.value.filterValues { it is InstallState.Installed }.keys.map { it.value }.sorted()))
            put("memAvailableMb", memAvailableMb())
        })
    }

    /** One artifact per pack model (ArtifactSelector.choose on this device), downloaded and prepared like the hub does. */
    private fun prepare() = runBlocking {
        val c = E2e.container
        if (arg("emulator") == "1") emulatorKnobs(models = null)
        val catalog = c.catalog.catalog.value
        val profile = c.deviceProfile.ensure()
        val settings = c.settings.settings.value
        val packId = arg("pack")
        val wanted: List<Pair<String, ArtifactId>> = arg("artifacts")?.split(',')?.map { a ->
            val (entry, _) = catalog.artifact(ArtifactId(a)) ?: throw AssertionError("unknown artifact $a")
            entry.id.value to ArtifactId(a)
        } ?: run {
            val id = packId ?: "recommended"
            val pack = catalog.packs.firstOrNull { it.id == id } ?: throw AssertionError("no pack $id in the catalog")
            pack.items.map { modelId ->
                val entry = catalog.model(modelId) ?: throw AssertionError("pack $id names unknown model ${modelId.value}")
                val pref = settings.unitPreference[modelId.value] ?: UnitPreference.Auto
                val chosen = ArtifactSelector.choose(entry, profile, pref, health = c.unitHealth)
                    ?: throw AssertionError("nothing usable for ${modelId.value} on this device")
                modelId.value to chosen.artifact.id
            }
        }
        val results = JSONArray()
        for ((model, id) in wanted) {
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
                put("model", model); put("artifact", id.value)
                put("state", final::class.simpleName)
                if (final is InstallState.Failed) put("error", final.error.code)
                put("downloadMs", (preparingAt ?: end) - t0)
                put("preparingMs", preparingAt?.let { end - it } ?: JSONObject.NULL)
                put("health", JSONObject(ComputeUnit.entries.associate { u -> u.name to healthOf(id, u) }))
            })
        }
        for (a in arg("prepareInstalled")?.split(',').orEmpty()) {
            val id = ArtifactId(a)
            val model = catalog.artifact(id)?.first?.id?.value ?: throw AssertionError("unknown artifact $a")
            val t0 = SystemClock.uptimeMillis()
            val r = c.orchestrator.prepareArtifact(id) {}
            val report = (r as? app.murmur.core.model.Outcome.Ok)?.value
            results.put(JSONObject().apply {
                put("model", model); put("artifact", a); put("pushed", true)
                put("state", if (report != null && report.proven.isNotEmpty()) "Installed" else "Failed")
                (r as? app.murmur.core.model.Outcome.Err)?.let { put("error", it.error.code) }
                report?.failed?.entries?.firstOrNull()?.let { put("error", "${it.key.name}:${it.value.code}") }
                put("downloadMs", 0)
                put("preparingMs", SystemClock.uptimeMillis() - t0)
                put("health", JSONObject(ComputeUnit.entries.associate { u -> u.name to healthOf(id, u) }))
            })
        }
        write("prepare", JSONObject().put("pack", packId ?: JSONObject.NULL).put("artifacts", results).put("memAvailableMb", memAvailableMb()))
    }

    private fun healthOf(id: ArtifactId, unit: ComputeUnit): String = when {
        E2e.container.unitHealth.failed(id, unit) -> "failed"
        E2e.container.unitHealth.proven(id, unit) -> "proven"
        else -> "unknown"
    }

    private data class Case(
        val name: String, val seconds: Long, val preIdleSec: Long?, val edit: Boolean, val pin: ComputeUnit?, val timeoutScale: Float?,
        val probe: Boolean,
    )

    private fun parse(spec: String): Case {
        val parts = spec.split(':')
        require(parts.size >= 2) { "case $spec: <name>:<seconds>[:idle<N>][:edit][:pin<unit>][:scale<x>][:probe]" }
        val flags = parts.drop(2)
        return Case(
            name = parts[0], seconds = parts[1].toLong(),
            preIdleSec = flags.firstOrNull { it.startsWith("idle") }?.removePrefix("idle")?.toLong(),
            edit = "edit" in flags,
            pin = flags.firstOrNull { it.startsWith("pin") }?.removePrefix("pin")?.let { ComputeUnit.valueOf(it.uppercase()) },
            timeoutScale = flags.firstOrNull { it.startsWith("scale") }?.removePrefix("scale")?.toFloat(),
            probe = "probe" in flags,
        )
    }

    private fun dictateAll(spec: String) {
        val cases = spec.split(',').map(::parse)
        val models = arg("models")?.split(',')
        if (arg("emulator") == "1") runBlocking { emulatorKnobs(models) }
        else if (models != null) runBlocking {
            require(models.size == 2) { "dc8.models <asrModel>,<llmModel>" }
            E2e.container.settings.update { it.copy(asrModel = ModelId(models[0]), cleanup = CleanupChoice.Model(ModelId(models[1]))) }
        }
        var lastDoneAt = 0L
        for (case in cases) {
            lastDoneAt = dictateCase(case)
            E2e.waitUntil("Idle after ${case.name}", 30_000, pollMs = 100) { E2e.container.orchestrator.state.value is DictationState.Idle }
        }
        arg("idleCheckSec")?.toLong()?.let { idle ->
            var asrGone: Long? = null
            var llmGone: Long? = null
            while (SystemClock.uptimeMillis() < lastDoneAt + idle * 1_000) {
                val t = SystemClock.uptimeMillis() - lastDoneAt
                if (asrGone == null && Processes.pidOf(Processes.ASR) == null) asrGone = t
                if (llmGone == null && Processes.pidOf(Processes.LLM) == null) llmGone = t
                SystemClock.sleep(1_000)
            }
            write("idle", JSONObject().put("idleCheckSec", idle)
                .put("asrExitAfterDoneMs", asrGone ?: JSONObject.NULL).put("llmExitAfterDoneMs", llmGone ?: JSONObject.NULL)
                .put("asrRunning", Processes.pidOf(Processes.ASR) != null).put("llmRunning", Processes.pidOf(Processes.LLM) != null)
                .put("memAvailableMb", memAvailableMb()))
        }
        if (arg("cancelCheck") == "1") cancelDuringArming()
    }

    /** WP-05's DC8 note: a press cancelled while :asr is still being bound or loaded must not leave the process running. */
    private fun cancelDuringArming() {
        val c = E2e.container
        val idleSec = c.settings.settings.value.idleUnloadSec.toLong()
        rec.states.clear()
        historyBefore = Pipeline.historyCount()
        TargetApp.open(TargetApp.Target.PLAIN)
        val asrBefore = Processes.pidOf(Processes.ASR) != null
        Bubble.tapToStart()
        val at = rec.awaitState("Arming, or Listening with the ASR still loading", 30_000) { s ->
            s.takeIf { it is DictationState.Arming || (it is DictationState.Listening && it.engines.asr is AsrReadiness.Loading) }
        }
        c.orchestrator.onInput(app.murmur.core.dictation.UserInput.Cancel)
        E2e.waitUntil("Idle after the cancel", 30_000, pollMs = 100) { c.orchestrator.state.value is DictationState.Idle }
        val cancelledAt = SystemClock.uptimeMillis()
        var asrGone: Long? = null
        while (SystemClock.uptimeMillis() < cancelledAt + (idleSec + 2) * 1_000) {
            if (asrGone == null && Processes.pidOf(Processes.ASR) == null) asrGone = SystemClock.uptimeMillis() - cancelledAt
            SystemClock.sleep(500)
        }
        write("cancel", JSONObject().put("cancelledIn", at::class.simpleName).put("idleUnloadSec", idleSec)
            .put("asrRunningBefore", asrBefore).put("asrExitAfterCancelMs", asrGone ?: JSONObject.NULL)
            .put("asrRunningAfter", Processes.pidOf(Processes.ASR) != null).put("llmRunningAfter", Processes.pidOf(Processes.LLM) != null)
            .put("historyRows", Pipeline.historyCount() - historyBefore))
    }

    /**
     * The CPU emulator (dry run): [models] selected (asr, llm) when given, the cleanup model pinned to CPU, the memory
     * gate ignored and timeouts scaled (debug knobs; x8 as in Pipeline.timeoutScale).
     */
    private suspend fun emulatorKnobs(models: List<String>?) {
        E2e.container.settings.update { s ->
            var n = s
            if (models != null) {
                require(models.size == 2) { "dc8.models <asrModel>,<llmModel>" }
                n = n.copy(asrModel = ModelId(models[0]), cleanup = CleanupChoice.Model(ModelId(models[1])))
            }
            val llm = (n.cleanup as? CleanupChoice.Model)?.id?.value ?: Pipeline.LLM_MODEL
            n.copy(
                unitPreference = n.unitPreference + (llm to UnitPreference.Pinned(ComputeUnit.CPU)),
                debugIgnoreMemoryGate = true,
                debugTimeoutScale = Pipeline.timeoutScale,
            )
        }
    }

    /** One scripted dictation; returns the uptime of its Done. */
    private fun dictateCase(case: Case): Long {
        val c = E2e.container
        val wavDir = arg("wavDir")
        if (wavDir != null) {
            val path = "$wavDir/${case.name}.wav"
            val bytes = E2e.uiAutomation.executeShellCommand("cat $path").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() } }
            check(bytes.size > 44) { "$path is missing" }
            Fixtures.captureFile.writeBytes(bytes)
        } else {
            Fixtures.captureFile.delete()   // the real microphone
        }
        case.pin?.let { unit ->
            runBlocking {
                c.settings.update { s ->
                    val llm = (s.cleanup as? CleanupChoice.Model)?.id ?: c.catalog.catalog.value.defaults.llm
                        ?: throw AssertionError("case ${case.name}: no cleanup model selected and no catalog default")
                    s.copy(unitPreference = s.unitPreference + (llm.value to UnitPreference.Pinned(unit)))
                }
            }
        }
        val out = JSONObject().put("case", case.name).put("seconds", case.seconds).put("pinnedLlmUnit", case.pin?.name ?: JSONObject.NULL)
        case.preIdleSec?.let { idle ->
            SystemClock.sleep(idle * 1_000)
            out.put("preIdleSec", idle)
            out.put("asrRunningBefore", Processes.pidOf(Processes.ASR) != null)
            out.put("llmRunningBefore", Processes.pidOf(Processes.LLM) != null)
        }
        val scaleBefore = c.settings.settings.value.debugTimeoutScale
        case.timeoutScale?.let { x -> runBlocking { c.settings.update { it.copy(debugTimeoutScale = x) } } }
        out.put("timeoutScale", c.settings.settings.value.debugTimeoutScale.toDouble())
        val preferencesBefore = c.settings.settings.value.preferences
        if (case.edit) {
            val shaBefore = prefixSha()
            runBlocking { c.settings.update { it.copy(preferences = edited(it.preferences)) } }
            val shaAfter = prefixSha()
            out.put("edit", JSONObject()
                .put("prefixShaBefore", shaBefore?.take(16) ?: JSONObject.NULL).put("prefixShaAfter", shaAfter?.take(16) ?: JSONObject.NULL)
                .put("prefixChanged", shaBefore != null && shaAfter != null && shaBefore != shaAfter)
                .put("previousWarmLlmReadyAfterPressMs", lastWarmLlmReadyMs ?: JSONObject.NULL))
        }
        rec.states.clear()
        historyBefore = Pipeline.historyCount()
        TargetApp.open(TargetApp.Target.PLAIN)
        val memBefore = memAvailableMb()
        val llmWarmAtPress = Processes.pidOf(Processes.LLM) != null
        E2e.note("dc8 speak case=${case.name} seconds=${case.seconds}")
        val pressAt = SystemClock.uptimeMillis()
        dictate(listenMs = case.seconds * 1_000, listeningTimeoutMs = 30_000) { E2e.note("dc8 listening case=${case.name}") }
        val stopAt = SystemClock.uptimeMillis()
        val done = rec.awaitDone(300_000)
        val doneAt = SystemClock.uptimeMillis()
        if (case.timeoutScale != null) runBlocking { c.settings.update { it.copy(debugTimeoutScale = scaleBefore) } }
        if (case.edit) runBlocking { c.settings.update { it.copy(preferences = preferencesBefore) } }
        E2e.note("dc8 done case=${case.name}")
        // Press -> the LLM readiness of this dictation turned Ready: a warm hit with the same prefix is fast; a new prefix
        // (edit) is prefilled again, a cold start loads the model first.
        val llmReadyAfterPressMs = rec.states.firstOrNull { rec.enginesOf(it.value)?.llm is LlmReadiness.Ready }?.let { it.atMs - pressAt }
        if (llmWarmAtPress && !case.edit && llmReadyAfterPressMs != null) lastWarmLlmReadyMs = llmReadyAfterPressMs
        val asr = rec.states.firstNotNullOfOrNull { rec.enginesOf(it.value)?.asr as? AsrReadiness.Ready }
        val llm = rec.states.firstNotNullOfOrNull { rec.enginesOf(it.value)?.llm as? LlmReadiness.Ready }
        val engines = rec.states.mapNotNull { rec.enginesOf(it.value) }.lastOrNull()
        val row = if (Pipeline.historyCount() > historyBefore) Pipeline.historyEntries().maxBy { it.createdAt } else null
        out.apply {
            put("outcome", done.outcome::class.simpleName)
            (done.outcome as? DictationOutcome.Failed)?.let { put("error", it.error.code) }
            (done.outcome as? DictationOutcome.Inserted)?.let { put("noCleanup", it.noCleanup); put("skipReason", it.skipReason ?: JSONObject.NULL) }
            put("asr", asr?.let { JSONObject().put("unit", it.info.unit.name).put("deviceName", it.info.deviceName).put("loadMs", it.info.loadMs).put("fellBack", it.fellBack) } ?: JSONObject.NULL)
            put("llm", llm?.let { JSONObject().put("unit", it.info.unit.name).put("deviceName", it.info.deviceName).put("loadMs", it.info.loadMs).put("prefixTokens", it.prefixTokens).put("nCtx", it.nCtx).put("fellBack", it.fellBack) } ?: JSONObject.NULL)
            put("asrCrashes", engines?.asrCrashes ?: 0); put("llmCrashes", engines?.llmCrashes ?: 0)
            put("pressToStopMs", stopAt - pressAt); put("stopToDoneMsTest", doneAt - stopAt)
            put("llmWarmAtPress", llmWarmAtPress); put("llmReadyAfterPressMs", llmReadyAfterPressMs ?: JSONObject.NULL)
            row?.let { r ->
                put("cleanupOutcome", r.cleanup?.outcome ?: JSONObject.NULL)
                // ADR-088: the prompt profile and the tone the cleanup ran with (none = the category style line).
                put("promptVersion", r.cleanup?.promptVersion ?: JSONObject.NULL)
                put("cleanupTone", r.cleanup?.tone ?: JSONObject.NULL)
                put("insertMethod", r.insertMethod?.name ?: JSONObject.NULL)
                put("timings", JSONObject().put("recordMs", r.timings.recordMs).put("speechMs", r.timings.speechMs)
                    .put("stopToDoneMs", r.timings.stopToDoneMs ?: JSONObject.NULL).put("asrTailMs", r.timings.asrTailMs ?: JSONObject.NULL)
                    .put("llmMs", r.timings.llmMs ?: JSONObject.NULL).put("ocrMs", r.timings.ocrMs ?: JSONObject.NULL))
                put("asrArtifact", r.asr?.artifactId?.value ?: JSONObject.NULL)
                put("audioCodec", r.audio?.codec ?: JSONObject.NULL)
                put("llmArtifact", r.cleanup?.model?.artifactId?.value ?: JSONObject.NULL)
            }
            put("insertedText", TargetApp.lastText() ?: JSONObject.NULL)
            put("memAvailableBeforeMb", memBefore); put("memAvailableAtDoneMb", memAvailableMb())
            val failedUnits = JSONArray()
            for (id in c.installed.states.value.keys) for (u in ComputeUnit.entries) if (c.unitHealth.failed(id, u)) failedUnits.put("${id.value}:${u.name}")
            put("unitHealthFailed", failedUnits)
        }
        write("dictate-${case.name}", out)
        if (case.probe) {
            write("probe-${case.name}", JSONObject().put("case", case.name)
                .put("captureStop", captureStopProbe())
                .put("asrSession", asrSessionProbe(row, asr?.info?.unit)))
        }
        return doneAt
    }

    /** Press -> LLM Ready of the last warm dictation without an edit (the edit case's baseline). */
    private var lastWarmLlmReadyMs: Long? = null

    /** The edit: [EDIT_SENTENCE] added on its own line, or removed when a stopped run left it in the user's text. */
    private fun edited(preferences: String?): String {
        val p = preferences.orEmpty()
        val next = if (p.contains(EDIT_SENTENCE)) p.replace(EDIT_SENTENCE, "").trim() else listOf(p.trim(), EDIT_SENTENCE).filter { it.isNotEmpty() }.joinToString("\n")
        check(next.length <= PromptEditRules.MAX_PREFERENCES_CHARS) { "My preferences would exceed ${PromptEditRules.MAX_PREFERENCES_CHARS} characters" }
        return next
    }

    /**
     * sha256 of the cache prefix the selected catalog model gets for the current settings: Cleaner.prefix =
     * renderer.cachePrefix(profile, template, preferences, the user's system edit where it applies, ADR-046). Null when
     * no catalog LLM is selected.
     */
    private fun prefixSha(): String? {
        val c = E2e.container
        val s = c.settings.settings.value
        val catalog = c.catalog.catalog.value
        val id = (s.cleanup as? CleanupChoice.Model)?.id ?: catalog.defaults.llm ?: return null
        val traits = catalog.model(id)?.llm ?: return null
        val profile = c.prompts.profile(traits.promptProfile)
        val override = s.promptOverride?.takeIf { PromptEditRules.overrideApplies(profile, it.baseProfile) }?.systemText
        return PrefixCacheKey.sha256Hex(c.renderer.cachePrefix(profile, traits.template, s.preferences, override))
    }

    /** WP-09's DC8 note: stop() with keepRecordings on, while :asr and :llm run, returns in < 200 ms; the file's codec. */
    private fun captureStopProbe(): JSONObject = runBlocking {
        val c = E2e.container
        val capture = AndroidAudioCapture(E2e.context, c.audioStore)
        val stops = JSONArray()
        val codecs = sortedSetOf<String>()
        var asrUp = true
        var llmUp = true
        var error: String? = null
        for (i in 0 until CAPTURE_PROBES) {
            val session = SessionId(CAPTURE_PROBE_SESSION + i)
            val h = when (val r = capture.start(session, keepRecording = true, audioKey = AudioKey.of(System.currentTimeMillis(), session).value)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> { error = r.error.code; break }
            }
            delay(CAPTURE_PROBE_MS)
            asrUp = asrUp && Processes.pidOf(Processes.ASR) != null
            llmUp = llmUp && Processes.pidOf(Processes.LLM) != null
            val t0 = SystemClock.uptimeMillis()
            val result = h.stop()
            stops.put(SystemClock.uptimeMillis() - t0)
            result.audio?.let { a -> codecs += a.codec; c.audioStore.delete(a) }
        }
        JSONObject().put("stopMs", stops).put("codecs", JSONArray(codecs.toList()))
            .put("asrRunning", asrUp).put("llmRunning", llmUp).put("error", error ?: JSONObject.NULL)
    }

    /**
     * WP-06's DC8 note: the loaded ASR (Parakeet on the NPU on the phone) through AsrService reports Transcript.unit
     * and finishes within 3 s of the end of audio. Replays [row]'s stored recording in real time (20 ms frames) into a
     * new session of the ASR the dictation left loaded, then times finish() from the last sample.
     */
    private fun asrSessionProbe(row: HistoryEntry?, loadedUnit: ComputeUnit?): JSONObject = runBlocking {
        val c = E2e.container
        val out = JSONObject().put("loadedUnit", loadedUnit?.name ?: JSONObject.NULL).put("asrArtifact", row?.asr?.artifactId?.value ?: JSONObject.NULL)
        val ref = row?.audio ?: return@runBlocking out.put("error", "the dictation has no history audio")
        val pcm = c.audioStore.readPcm(ref) ?: return@runBlocking out.put("error", "history audio unreadable")
        out.put("asrRunningBefore", Processes.pidOf(Processes.ASR) != null)
        val session = c.engines.asr.startSession(AsrSessionOptions())
        val t0 = SystemClock.uptimeMillis()
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + PcmFormat.FRAME_SAMPLES, pcm.size)
            session.accept(PcmChunk(pcm.copyOfRange(i, end), i.toLong()))
            i = end
            val due = t0 + PcmFormat.samplesToMs(i.toLong())
            val wait = due - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
        }
        val eof = SystemClock.uptimeMillis()
        val r = withTimeoutOrNull(ASR_PROBE_FINISH_TIMEOUT_MS) { session.finish() }
        val finishMs = SystemClock.uptimeMillis() - eof
        if (r == null) session.cancel()
        out.put("audioMs", PcmFormat.samplesToMs(pcm.size.toLong())).put("finishAfterEofMs", finishMs)
        when (r) {
            null -> out.put("error", "finish() did not return within $ASR_PROBE_FINISH_TIMEOUT_MS ms")
            is Outcome.Err -> out.put("error", r.error.code)
            is Outcome.Ok -> out.put("transcriptUnit", r.value.unit.name).put("tailDecodeMs", r.value.tailDecodeMs)
                .put("segments", r.value.segments).put("words", r.value.text.split(Regex("\\s+")).count { it.isNotBlank() })
                .put("historyWords", row.rawText?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: JSONObject.NULL)
        }
        out
    }

    private companion object {
        /** A neutral style instruction: it changes the rendered prefix without changing what a correct cleanup is. */
        const val EDIT_SENTENCE = "Keep my own word order."
        const val CAPTURE_PROBES = 3
        const val CAPTURE_PROBE_MS = 1_500L
        const val CAPTURE_PROBE_SESSION = 9_000_000L
        const val ASR_PROBE_FINISH_TIMEOUT_MS = 30_000L
    }
}
