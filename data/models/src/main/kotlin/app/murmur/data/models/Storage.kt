package app.murmur.data.models

import app.murmur.core.catalog.Artifact
import app.murmur.core.catalog.CatalogValidator
import app.murmur.core.catalog.FileSpec
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.Outcome
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-disk layout (ARCHITECTURE.md 8.3). Models live in noBackupFilesDir (never in backups, never in the APK):
 *   models/<artifactId>/<file.name>            downloaded files (archives extracted in place, then deleted)
 *   models/<artifactId>/<file.name>.part       in-progress download (its length = resume offset)
 *   models/<artifactId>/<file.name>.extracted  stamp: the archive was verified and extracted (it may be deleted)
 *   models/<artifactId>/installed.json         written last, atomically: the artifact is usable iff it exists
 *   models/<artifactId>/prefix-<key>.state     LLM prefix snapshots (ADR-011), deleted with the artifact
 *   models/unit-health.json                    UnitHealth, reset per app version
 * The artifact id contains '@' and '.', both valid in file names; '/' never occurs (CatalogValidator).
 */
class ModelStorage(val root: File) {
    fun dir(id: ArtifactId) = File(root, id.value)
    fun file(id: ArtifactId, name: String) = File(dir(id), name)
    fun part(id: ArtifactId, name: String) = File(dir(id), "$name$PART")
    fun extractedStamp(id: ArtifactId, name: String) = File(dir(id), "$name$EXTRACTED")
    fun marker(id: ArtifactId) = File(dir(id), MARKER)
    fun isInstalled(id: ArtifactId) = marker(id).isFile
    val unitHealthFile: File get() = File(root, UNIT_HEALTH)

    fun readMarker(id: ArtifactId): InstalledMarker? = try {
        marker(id).takeIf { it.isFile }?.let { json.decodeFromString(InstalledMarker.serializer(), it.readText()) }
    } catch (e: Exception) {
        null   // a torn or foreign file is "not installed"; the next install rewrites it
    }

    fun writeMarker(marker: InstalledMarker) =
        writeAtomically(marker(ArtifactId(marker.artifactId)), json.encodeToString(InstalledMarker.serializer(), marker))

    /** Bytes this artifact's files have on disk right now: finished files, .part lengths, extracted archives. */
    fun bytesDone(a: Artifact): Long = a.files.sumOf { f ->
        when {
            file(a.id, f.name).isFile || extractedStamp(a.id, f.name).isFile -> f.bytes
            else -> part(a.id, f.name).length().coerceAtMost(f.bytes)
        }
    }

    /** Directory the layout paths are relative to: <dir>/<layout["dir"]> for archives that extract into one folder. */
    fun modelDir(a: Artifact): File = a.layout[CatalogValidator.LAYOUT_DIR]?.let { File(dir(a.id), it) } ?: dir(a.id)

    /** Layout roles whose file is missing (empty = complete). */
    fun missingLayout(a: Artifact): List<String> {
        val base = modelDir(a)
        return a.layout.filterKeys { it != CatalogValidator.LAYOUT_DIR }.filter { (_, path) -> !File(base, path).exists() }.keys.toList()
    }

    fun sizeOnDisk(id: ArtifactId): Long = dir(id).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun totalBytes(): Long = if (root.isDirectory) root.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L

    fun delete(id: ArtifactId): Boolean = dir(id).deleteRecursively()

    /** Artifact directories present on disk. */
    fun artifactDirs(): List<ArtifactId> = root.listFiles()?.filter { it.isDirectory }?.map { ArtifactId(it.name) }.orEmpty()

    companion object {
        const val MARKER = "installed.json"
        const val PART = ".part"
        const val EXTRACTED = ".extracted"
        const val UNIT_HEALTH = "unit-health.json"
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** tmp file + fsync + atomic rename: readers see the old or the new content, never a torn one. */
        fun writeAtomically(target: File, text: String) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.encodeToByteArray())
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** Content of installed.json: proves which files were verified, for which catalog version. */
@Serializable
data class InstalledMarker(
    val artifactId: String,
    val catalogVersion: String,
    val files: Map<String, String>,      // file name -> sha256 verified
    val bytesOnDisk: Long,
    val installedAtMs: Long,
    /** EnginePreparer finished (ARCHITECTURE 8.5); false = a rerun of the job prepares again. */
    val prepared: Boolean = false,
)

/**
 * One file transfer: HTTPS GET with Range resume from the .part length; every redirect target must match
 * CatalogValidator.allowedRedirectHosts; SHA-256 streamed while writing; fsync; atomic rename (WP-13).
 * Returns the final file (the part name without ".part").
 */
interface Downloader {
    suspend fun fetch(url: String, part: File, expectedBytes: Long, expectedSha256: String, onProgress: (Long) -> Unit): Outcome<File>
}

/** tar.bz2 / zip extraction with path-traversal protection (entries must stay inside the artifact dir). WP-13. */
interface ArchiveExtractor {
    fun extract(archive: File, intoDir: File, type: app.murmur.core.catalog.ArchiveType): Outcome<List<File>>
}

/** Bytes needed on disk during install: archive + extracted size (archives double the peak). */
fun peakInstallBytes(a: Artifact): Long = a.files.sumOf { if (it.archive != null) it.bytes * 2 else it.bytes }

/** The GGUF file of an LLM artifact (layout "model", else the first *.gguf). */
fun ggufFile(a: Artifact): FileSpec? =
    a.files.firstOrNull { it.name == a.layout["model"] } ?: a.files.firstOrNull { it.name.endsWith(".gguf") }
