package app.murmur.data.models

import app.murmur.core.catalog.Artifact
import app.murmur.core.catalog.AsrTraits
import app.murmur.core.catalog.CatalogValidator
import app.murmur.core.catalog.CustomModelRequest
import app.murmur.core.catalog.CustomModelResolver
import app.murmur.core.catalog.FileSource
import app.murmur.core.catalog.FileSpec
import app.murmur.core.catalog.GgufTemplates
import app.murmur.core.catalog.Languages
import app.murmur.core.catalog.License
import app.murmur.core.catalog.LlmTraits
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.catalog.Origin
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.catalog.SherpaLayout
import app.murmur.core.catalog.Tier
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * D3 "the user can configure any model" (ADR-046, ARCHITECTURE 8.6): a Hugging Face repo -> a validated
 * [ModelEntry] with origin CUSTOM, pinned to a full commit sha, every file with size and sha256.
 *  1. `GET /api/models/<repo>/revision/<rev>` -> commit sha, file list, card data (languages, licence); gated or
 *     private repos are refused (no tokens, D9);
 *  2. LLM: one GGUF (the request's file, else Q4_0 preferred, then Q4_K_M, never mmproj or split files); its header is
 *     read with HTTP Range requests (4 MB steps, at most 16 MB) for `general.architecture` -> template, `general.name` and
 *     `murmur.prompt_profile` -> profile (the request's profile wins), the attention shape -> kvBytesPerToken;
 *     nCtx = [GgufTemplates.DEFAULT_CUSTOM_NCTX] until Preparing measures the prefix;
 *     ASR: [SherpaLayout.detect] over the file list;
 *  3. `POST /api/models/<repo>/paths-info/<sha>` -> sizes + LFS sha256; small non-LFS files are downloaded and hashed;
 *  4. [CatalogValidator.validateEntry] must pass.
 * Errors are [MurmurError.CatalogInvalid] with a readable reason (unsupported architecture, no GGUF, no layout),
 * [MurmurError.DownloadFailed] (HTTP status, gated) or [MurmurError.NoNetwork].
 */
class HfCustomModelResolver(
    private val http: HttpTransport = UrlConnectionTransport(),
    private val log: DlLog = DlLog.NONE,
) : CustomModelResolver {

    /** Bytes of GGUF header the last LLM resolve fetched (reported by the acceptance test). */
    @Volatile var lastHeaderBytes: Long = 0
        private set

    override suspend fun resolve(request: CustomModelRequest): Outcome<ModelEntry> = withContext(Dispatchers.IO) {
        try {
            resolveOrThrow(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Rejected) {
            log.event("custom_rejected", "kind" to request.kind.name, "reason" to e.message)
            Outcome.Err(e.error)
        } catch (e: IOException) {
            Outcome.Err(FileDownloader.networkError(e))
        } catch (e: Exception) {
            Outcome.Err(MurmurError.CatalogInvalid("unexpected response: ${e.javaClass.simpleName}"))
        }
    }

    private class Rejected(val error: MurmurError) : Exception(error.detail)

    private fun reject(reason: String): Nothing = throw Rejected(MurmurError.CatalogInvalid(reason))

    private fun resolveOrThrow(req: CustomModelRequest): Outcome<ModelEntry> {
        if (!REPO.matches(req.repo)) reject("not a Hugging Face repo id (owner/name)")
        req.revision?.let { if (!REV.matches(it)) reject("bad revision") }
        val info = getJson("$API/models/${req.repo}/revision/${req.revision ?: "main"}").jsonObject
        val sha = info.str("sha")?.takeIf { SHA1.matches(it) } ?: reject("revision did not resolve to a commit")
        val gated = info["gated"]
        if (info.str("private") == "true" || (gated is JsonPrimitive && gated.content != "false"))
            throw Rejected(MurmurError.DownloadFailed(401, "gated or private repo"))
        val siblings = info["siblings"]?.jsonArray?.mapNotNull { it.jsonObject.str("rfilename") }.orEmpty()
        val card = info["cardData"] as? JsonObject
        val entry = when (req.kind) {
            ModelKind.LLM -> llmEntry(req, sha, siblings, card)
            ModelKind.ASR -> asrEntry(req, sha, siblings, card, info)
            else -> reject("custom ${req.kind.name.lowercase()} models are not supported")
        }
        val problems = CatalogValidator.validateEntry(entry)
        if (problems.isNotEmpty()) reject(problems.joinToString("; ").take(300))
        log.event("custom_resolved", "kind" to req.kind.name, "files" to entry.artifacts.single().files.size)
        return Outcome.Ok(entry)
    }

    private fun llmEntry(req: CustomModelRequest, sha: String, siblings: List<String>, card: JsonObject?): ModelEntry {
        val path = req.file?.also { if (it !in siblings) reject("file $it is not in the repo") } ?: pickGguf(siblings)
            ?: reject("no single-file GGUF in the repo")
        if (!path.endsWith(".gguf", ignoreCase = true)) reject("$path is not a GGUF file")
        val file = fileSpec(req.repo, sha, path, "model.gguf")
        // An unsupported architecture is known from the first bytes: no need to read the rest of the header.
        val meta = readHeader(file.url) { m -> req.llmTemplate == null && m.string("general.architecture")?.let { GgufTemplates.templateFor(it) == null } == true }
        val arch = meta.string("general.architecture") ?: reject("GGUF header has no general.architecture")
        val template = req.llmTemplate ?: GgufTemplates.templateFor(arch)
            ?: reject("unsupported GGUF architecture '$arch'; supported: ${GgufTemplates.byArchitecture.keys.joinToString(", ")}")
        if (template !in GgufTemplates.byArchitecture.values) reject("unknown template $template")
        val profile = req.promptProfile ?: GgufTemplates.profileFor(meta.string("general.name"), meta.string(GgufTemplates.PROFILE_KEY))
        val quant = quantOf(path)
        val id = customId(req.repo)
        return ModelEntry(
            id = id,
            kind = ModelKind.LLM,
            displayName = req.displayName ?: meta.string("general.name") ?: req.repo.substringAfter('/'),
            publisher = req.repo.substringBefore('/'),
            family = arch,
            baseRepo = req.repo,
            languages = languages(card, emptyList()),
            license = license(card),
            tier = Tier.OPTIONAL,
            llm = LlmTraits(
                template = template,
                promptProfile = profile,
                nCtx = GgufTemplates.DEFAULT_CUSTOM_NCTX,
                prefixTokens = null,
                kvBytesPerToken = GgufHeader.kvBytesPerToken(meta),
            ),
            artifacts = listOf(
                Artifact(
                    id = ArtifactId("${id.value}@${quant ?: "gguf"}"),
                    runtime = RuntimeKeys.LLAMA_GGUF,
                    format = "gguf",
                    quant = quant,
                    // Claims like the bundled GGUFs (ADR-009); Preparing proves each unit on this phone.
                    units = listOf(ComputeUnit.NPU, ComputeUnit.GPU, ComputeUnit.CPU),
                    preferredUnit = ComputeUnit.CPU,
                    files = listOf(file),
                    layout = mapOf("model" to file.name),
                ),
            ),
            notes = "custom: GGUF architecture $arch; header read ${lastHeaderBytes} bytes",
            origin = Origin.CUSTOM,
        )
    }

    private fun asrEntry(req: CustomModelRequest, sha: String, siblings: List<String>, card: JsonObject?, info: JsonObject): ModelEntry {
        val candidates = siblings.filter { it.endsWith(".onnx") || it.endsWith("tokens.txt") }
            .filterNot { p -> p.split('/').dropLast(1).any { it.startsWith("test") } }
        val (detected, roles) = SherpaLayout.detect(candidates) ?: reject("no sherpa-onnx layout (encoder/decoder/joiner or model + tokens.txt)")
        val runtime = req.runtime ?: detected
        if (runtime !in RuntimeKeys.implemented || RuntimeKeys.kindOf(runtime) != ModelKind.ASR) reject("runtime $runtime is not supported")
        val names = roles.values.map { it.substringAfterLast('/') }
        if (names.toSet().size != names.size) reject("layout files share a name")
        val infos = pathsInfo(req.repo, sha, roles.values.toList())
        val files = roles.values.distinct().map { p -> fileSpec(req.repo, sha, p, p.substringAfterLast('/'), infos[p]) }
        val id = customId(req.repo)
        val tags = info["tags"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        return ModelEntry(
            id = id,
            kind = ModelKind.ASR,
            displayName = req.displayName ?: req.repo.substringAfter('/'),
            publisher = req.repo.substringBefore('/'),
            family = "custom",
            baseRepo = req.repo,
            languages = languages(card, tags),
            license = license(card),
            tier = Tier.OPTIONAL,
            // Unknown from the file list: the conservative traits (the cleanup step adds punctuation and casing).
            asr = AsrTraits(streaming = runtime.startsWith("sherpa/online-"), punctuation = false, casing = false),
            artifacts = listOf(
                Artifact(
                    id = ArtifactId("${id.value}@onnx"),
                    runtime = runtime,
                    format = "onnx",
                    quant = if (roles.values.any { "int8" in it }) "int8" else null,
                    units = listOf(ComputeUnit.CPU),
                    files = files,
                    layout = roles.mapValues { it.value.substringAfterLast('/') },
                ),
            ),
            notes = "custom: sherpa-onnx layout $runtime",
            origin = Origin.CUSTOM,
        )
    }

    /** One file pinned to [sha] with its size and sha256 (LFS oid, or hashed here for small non-LFS files). */
    private fun fileSpec(repo: String, sha: String, path: String, name: String, known: JsonObject? = null): FileSpec {
        val info = known ?: pathsInfo(repo, sha, listOf(path))[path] ?: reject("$path not found at $sha")
        val size = info["size"]?.jsonPrimitive?.longOrNull ?: reject("$path has no size")
        val url = "https://huggingface.co/$repo/resolve/$sha/${encodePath(path)}"
        val lfsSha = (info["lfs"] as? JsonObject)?.str("oid")?.takeIf { SHA256.matches(it) }
        val sha256 = lfsSha ?: if (size <= SMALL_FILE_BYTES) hashSmall(url, size) else reject("$path has no sha256 (not an LFS file)")
        return FileSpec(name = name, url = url, bytes = size, sha256 = sha256, source = FileSource.HUGGINGFACE,
            repo = repo, revision = sha, path = path)
    }

    private fun pathsInfo(repo: String, sha: String, paths: List<String>): Map<String, JsonObject> {
        val body = buildJsonObject { putJsonArray("paths") { paths.forEach { add(JsonPrimitive(it)) } }; put("expand", false) }
        val rows = http.post("$API/models/$repo/paths-info/$sha", body.toString().encodeToByteArray(), "application/json").use { r ->
            if (r.code != 200) throw Rejected(MurmurError.DownloadFailed(r.code, "paths-info"))
            json.parseToJsonElement(r.body.readBytes().decodeToString()).jsonArray
        }
        return rows.map { it.jsonObject }.associateBy { it.str("path").orEmpty() }
    }

    private fun hashSmall(url: String, size: Long): String = http.get(url).use { r ->
        if (r.code != 200) throw Rejected(MurmurError.DownloadFailed(r.code, "small file"))
        val bytes = r.body.readNBytes((size + 1).toInt())
        if (bytes.size.toLong() != size) reject("size of a small file does not match the API")
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    /** GGUF metadata from the first 4 MB, extended 4 MB at a time (to 16 MB) while the key/values continue (tokenizer arrays). */
    private fun readHeader(url: String, enough: (GgufHeader.Meta) -> Boolean = { false }): GgufHeader.Meta {
        val buf = ByteArrayOutputStream()
        var meta: GgufHeader.Meta? = null
        for (end in HEADER_STEPS) {
            val from = buf.size().toLong()
            http.get(url, mapOf("Range" to "bytes=$from-${end - 1}")).use { r ->
                when (r.code) {
                    206 -> r.body.copyTo(buf)
                    200 -> { if (from > 0) reject("server ignored the Range request"); buf.write(r.body.readNBytes(end.toInt())) }
                    416 -> Unit   // the file is shorter than this step: what we have is all of it
                    else -> throw Rejected(MurmurError.DownloadFailed(r.code, "gguf header"))
                }
            }
            val bytes = buf.toByteArray()
            meta = try { GgufHeader.parse(bytes) } catch (e: GgufHeader.NotGguf) { reject("not a GGUF file (${e.message})") }
            if (meta.complete || enough(meta) || buf.size().toLong() < end) break
        }
        lastHeaderBytes = buf.size().toLong()
        log.event("gguf_header", "bytes" to lastHeaderBytes, "complete" to meta?.complete, "keys" to meta?.values?.size)
        return meta!!
    }

    private fun getJson(url: String): JsonElement = http.get(url).use { r ->
        when (r.code) {
            200 -> json.parseToJsonElement(r.body.readBytes().decodeToString())
            401, 403 -> throw Rejected(MurmurError.DownloadFailed(r.code, "gated or private repo"))
            404 -> reject("repo or revision not found")
            else -> throw Rejected(MurmurError.DownloadFailed(r.code, "hub api"))
        }
    }

    companion object {
        const val API = "https://huggingface.co/api"
        const val SMALL_FILE_BYTES = 10L * 1024 * 1024
        /** Range steps for the GGUF header: 4 MB at a time, at most 16 MB (Qwen3.5 0.8B: 10.9 MB, LFM3 1.7B: 8.2 MB). */
        val HEADER_STEPS = listOf(4L shl 20, 8L shl 20, 12L shl 20, 16L shl 20)
        private val REPO = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9._-]+$")
        private val REV = Regex("^[A-Za-z0-9._/-]{1,100}$")
        private val SHA1 = Regex("^[0-9a-f]{40}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val json = Json { ignoreUnknownKeys = true }
        private val QUANT = Regex("""(?i)(?:^|[-_.])((?:UD-)?(?:I?Q\d(?:_[A-Z0-9]+)*|BF16|F16|F32))\.gguf$""")

        fun customId(repo: String): ModelId =
            ModelId("custom-" + repo.lowercase().replace('/', '-').replace(Regex("[^a-z0-9._-]"), "-"))

        /** "Qwen3.5-0.8B-Q4_0.gguf" -> "q4_0"; null when the name carries no quant. */
        fun quantOf(path: String): String? = QUANT.find(path.substringAfterLast('/'))?.groupValues?.get(1)?.lowercase()

        /** Q4_0 first (NPU-friendly, ADR-009), then Q4_K_M; never projector or split files. */
        fun pickGguf(siblings: List<String>): String? {
            val ggufs = siblings.filter { it.endsWith(".gguf", ignoreCase = true) }
                .filterNot { val n = it.substringAfterLast('/').lowercase(); n.startsWith("mmproj") || Regex("-\\d{5}-of-\\d{5}").containsMatchIn(n) }
            fun byQuant(q: String) = ggufs.firstOrNull { quantOf(it) == q }
            return byQuant("q4_0") ?: byQuant("q4_k_m") ?: ggufs.singleOrNull()
        }

        private fun encodePath(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

        private fun languages(card: JsonObject?, tags: List<String>): Languages {
            val fromCard = when (val l = card?.get("language")) {
                is JsonArray -> l.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> listOf(l.content)
                else -> emptyList()
            }
            val codes = (fromCard.ifEmpty { tags.filter { Regex("^[a-z]{2,3}$").matches(it) && it !in NOT_LANGUAGES } }).distinct()
            return Languages(codes = codes)
        }

        private val NOT_LANGUAGES = setOf("onnx", "tdt", "gguf", "asr", "llm", "ctc")

        private fun license(card: JsonObject?): License = License(id = card?.str("license") ?: "unknown")
    }
}
