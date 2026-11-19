package app.murmur.core.catalog

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.UnitPreference

/** One artifact a download request resolves to, and why it is in the plan. */
data class PlannedDownload(val model: ModelId, val artifact: Artifact, val reason: Reason) {
    enum class Reason {
        /** The model the user (or the pack) asked for. */
        REQUESTED,
        /** Batch ASR needs the default VAD (D4). */
        VAD_FOR_BATCH_ASR,
        /** "Use what's on screen" is on: the OCR det/rec pair (D11). */
        OCR_PAIR,
    }
}

/**
 * What a download queues (ADR-050, ARCHITECTURE 8.5). Pure: the hub, onboarding (pack sizes) and the installer use the
 * same rules.
 *  - every model resolves to one artifact: [ArtifactSelector.choose] over all artifacts (installed or not) with the
 *    model's unit preference; a pinned unit no artifact can serve falls back to Auto, so the user still gets a
 *    working build;
 *  - an ASR artifact with a batch runtime adds the default VAD;
 *  - with "Use what's on screen" on, the OCR pair is added; with it off, OCR models of a pack are left out.
 * Models with no usable artifact on this device (e.g. an NPU-only build on the emulator) contribute nothing.
 * The result is de-duplicated and keeps request order.
 */
object DownloadPlanner {
    fun forModels(
        catalog: Catalog,
        models: List<ModelId>,
        device: DeviceProfile,
        useScreen: Boolean,
        unitPreference: (ModelId) -> UnitPreference = { UnitPreference.Auto },
    ): List<PlannedDownload> {
        val out = LinkedHashMap<ArtifactId, PlannedDownload>()
        fun add(model: ModelId, reason: PlannedDownload.Reason) {
            val entry = catalog.model(model) ?: return
            val artifact = artifactFor(entry, device, unitPreference(model)) ?: return
            out.putIfAbsent(artifact.id, PlannedDownload(model, artifact, reason))
        }
        for (id in models) {
            val entry = catalog.model(id) ?: continue
            val isOcr = entry.kind == ModelKind.OCR_DET || entry.kind == ModelKind.OCR_REC
            if (isOcr && !useScreen) continue
            add(id, PlannedDownload.Reason.REQUESTED)
            val chosen = out.values.lastOrNull { it.model == id }?.artifact ?: continue
            if (entry.kind == ModelKind.ASR && RuntimeKeys.isBatchAsr(chosen.runtime))
                add(catalog.defaults.vad, PlannedDownload.Reason.VAD_FOR_BATCH_ASR)
        }
        if (useScreen) {
            add(catalog.defaults.ocrDet, PlannedDownload.Reason.OCR_PAIR)
            add(catalog.defaults.ocrRec, PlannedDownload.Reason.OCR_PAIR)
        }
        return out.values.toList()
    }

    fun forPack(catalog: Catalog, pack: Pack, device: DeviceProfile, useScreen: Boolean,
                unitPreference: (ModelId) -> UnitPreference = { UnitPreference.Auto }): List<PlannedDownload> =
        forModels(catalog, pack.items, device, useScreen, unitPreference)

    /** "Use what's on screen" turned on: the OCR pair only. */
    fun ocrPair(catalog: Catalog, device: DeviceProfile): List<PlannedDownload> =
        forModels(catalog, emptyList(), device, useScreen = true)

    /** The artifact a model downloads with this preference; a pinned unit nothing serves falls back to Auto. */
    fun artifactFor(entry: ModelEntry, device: DeviceProfile, pref: UnitPreference): Artifact? =
        (ArtifactSelector.choose(entry, device, pref) ?: ArtifactSelector.choose(entry, device, UnitPreference.Auto))?.artifact

    /** Sum of download sizes (onboarding "Download 1.7 GB"). */
    fun totalBytes(plan: List<PlannedDownload>): Long = plan.sumOf { it.artifact.downloadBytes }
}
