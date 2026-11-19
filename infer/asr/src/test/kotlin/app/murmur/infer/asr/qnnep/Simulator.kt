package app.murmur.infer.asr.qnnep

import app.murmur.core.catalog.CatalogParser
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ArtifactId
import java.io.File

/**
 * The host side of the WP-21 simulator suites (*SimulatorTest): Qualcomm's ORT plugin EP and the x86_64 HTP simulator
 * from the onnxruntime-qnn 2.8.0 wheel (QAIRT 2.52) of native/qnn-contexts/compile.sh's venv, and that script's output
 * (the exact files the catalog pins). Run:
 *   tools/gw :infer:asr:testDebugUnitTest --tests '*SimulatorTest' \
 *     -Pmurmur.qnnSim=<venv>/lib/python3.12/site-packages/onnxruntime_qnn -Pmurmur.qnnContexts=<compile.sh out dir>
 */
object Simulator {
    val libs = QnnEpLibs.inDir(requireNotNull(System.getProperty("murmur.qnnSim")) { "-Pmurmur.qnnSim not set" })
    val contexts = File(requireNotNull(System.getProperty("murmur.qnnContexts")) { "-Pmurmur.qnnContexts not set" })

    val ep: QnnEp by lazy { QnnEp.of(libs).requireAvailable() }

    /** The catalog's spec of [artifactId] over the compiled files in [contexts]. */
    fun spec(artifactId: String): LoadSpec {
        val catalog = CatalogParser.bundled()
        val (entry, a) = requireNotNull(catalog.artifact(ArtifactId(artifactId))) { artifactId }
        return LoadSpec(entry.kind, entry.id, a.id, a.runtime, a.units.single(), File(contexts, artifactId).path, a.layout, a.maxWindowSec)
    }
}
