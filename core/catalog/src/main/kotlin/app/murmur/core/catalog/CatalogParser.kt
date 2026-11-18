package app.murmur.core.catalog

import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import kotlinx.serialization.json.Json

/** Parses and validates a catalog document. Pure; used for the bundled copy, refreshes and tests. */
object CatalogParser {
    val json: Json = Json {
        ignoreUnknownKeys = true   // forward compatible: a newer refresh may add fields
        explicitNulls = false
        encodeDefaults = false
        prettyPrint = true
    }

    fun parse(text: String): Outcome<Catalog> {
        val catalog = try {
            json.decodeFromString(Catalog.serializer(), text)
        } catch (e: Exception) {
            return Outcome.Err(MurmurError.CatalogInvalid(e.message?.take(200)))
        }
        val problems = CatalogValidator.validate(catalog)
        return if (problems.isEmpty()) Outcome.Ok(catalog)
        else Outcome.Err(MurmurError.CatalogInvalid(problems.take(5).joinToString("; ")))
    }

    /** The catalog shipped in the APK (classpath resource, works on the JVM and on Android). */
    fun bundled(): Catalog {
        val text = CatalogParser::class.java.getResourceAsStream(BUNDLED_PATH)
            ?.use { it.readBytes().decodeToString() }
            ?: error("bundled catalog missing: $BUNDLED_PATH")
        return when (val r = parse(text)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> error("bundled catalog invalid: ${r.error.detail}")
        }
    }

    const val BUNDLED_PATH = "/catalog/catalog.json"
}

/** Invariants every catalog must satisfy. Returned as human-readable problems; empty = valid. */
object CatalogValidator {
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /** Download hosts (ADR-016). Redirect targets are checked by the downloader against [allowedRedirectHosts]. */
    val allowedUrlPrefixes = listOf("https://huggingface.co/", "https://github.com/")
    val allowedRedirectHosts = listOf("huggingface.co", ".huggingface.co", ".hf.co", "github.com", ".githubusercontent.com")

    fun validate(c: Catalog): List<String> {
        val p = mutableListOf<String>()
        if (c.schema != Catalog.SCHEMA) p += "schema ${c.schema} != ${Catalog.SCHEMA}"
        val ids = c.models.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { p += "duplicate model id $it" }
        val artIds = c.models.flatMap { m -> m.artifacts.map { it.id } }
        artIds.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { p += "duplicate artifact id $it" }
        fun ref(what: String, id: app.murmur.core.model.ModelId?) {
            if (id != null && c.model(id) == null) p += "$what references unknown model $id"
        }
        c.defaults.asr.forEach { (k, v) -> ref("defaults.asr[$k]", v) }
        ref("defaults.llm", c.defaults.llm); ref("defaults.vad", c.defaults.vad)
        ref("defaults.ocrDet", c.defaults.ocrDet); ref("defaults.ocrRec", c.defaults.ocrRec)
        c.packs.forEach { pk -> pk.items.forEach { ref("pack ${pk.id}", it) } }
        for (m in c.models) {
            if (m.status == Status.AVAILABLE && m.artifacts.isEmpty()) p += "${m.id}: available but no artifacts"
            when (m.kind) {
                app.murmur.core.model.ModelKind.ASR -> if (m.asr == null) p += "${m.id}: asr traits missing"
                app.murmur.core.model.ModelKind.LLM -> if (m.llm == null) p += "${m.id}: llm traits missing"
                app.murmur.core.model.ModelKind.OCR_DET, app.murmur.core.model.ModelKind.OCR_REC ->
                    if (m.ocr == null) p += "${m.id}: ocr traits missing"
                else -> Unit
            }
            for (a in m.artifacts) {
                if (a.runtime !in RuntimeKeys.known) p += "${a.id}: unknown runtime ${a.runtime}"
                if (a.units.isEmpty()) p += "${a.id}: no compute units"
                if (a.preferredUnit != null && a.preferredUnit !in a.units) p += "${a.id}: preferredUnit not in units"
                if (a.files.isEmpty()) p += "${a.id}: no files"
                for (f in a.files) {
                    if (!SHA256.matches(f.sha256)) p += "${a.id}/${f.name}: sha256 missing or malformed"
                    if (f.bytes <= 0) p += "${a.id}/${f.name}: bytes <= 0"
                    if (allowedUrlPrefixes.none { f.url.startsWith(it) }) p += "${a.id}/${f.name}: url host not allowed"
                    if (f.name.contains('/') || f.name.startsWith(".")) p += "${a.id}/${f.name}: bad local name"
                    if (f.source == FileSource.HUGGINGFACE && !f.url.contains("/resolve/${f.revision}/"))
                        p += "${a.id}/${f.name}: HF url not pinned to revision"
                }
            }
        }
        return p
    }
}
