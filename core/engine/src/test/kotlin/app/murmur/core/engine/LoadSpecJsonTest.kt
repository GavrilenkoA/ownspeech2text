package app.murmur.core.engine

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LoadSpecJsonTest {
    @Test
    fun loadSpecCrossesBinderAsJson() {
        val s = LoadSpec(ModelKind.ASR, ModelId("parakeet-tdt-0.6b-v3"), ArtifactId("parakeet-tdt-0.6b-v3@onnx-int8"),
            "sherpa/offline-transducer", ComputeUnit.CPU, "/data/x", mapOf("tokens" to "tokens.txt"), 20.0)
        assertEquals(s, Json.decodeFromString(LoadSpec.serializer(), Json.encodeToString(LoadSpec.serializer(), s)))
    }
}
