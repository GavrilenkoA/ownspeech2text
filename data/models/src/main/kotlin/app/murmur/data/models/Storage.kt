package app.murmur.data.models

import app.murmur.core.catalog.Artifact
import app.murmur.core.model.ArtifactId
import java.io.File
import kotlinx.serialization.Serializable

/**
 * On-disk layout (ARCHITECTURE.md 8.3). Models live in noBackupFilesDir (never in backups, never in the APK):
 *   models/<artifactId>/<file.name>         downloaded files (archives extracted in place, then deleted)
 *   models/<artifactId>/<file.name>.part    in-progress download (its length = resume offset)
 *   models/<artifactId>/installed.json      written last, atomically: the artifact is usable iff it exists
 *   models/<artifactId>/prefix-<key>.state  LLM prefix snapshots (ADR-011), deleted with the artifact
 * The artifact id contains '@' and '.', both valid in file names; '/' never occurs (CatalogValidator).
 */
class ModelStorage(val root: File) {
    fun dir(id: ArtifactId) = File(root, id.value)
    fun part(id: ArtifactId, name: String) = File(dir(id), "$name.part")
    fun marker(id: ArtifactId) = File(dir(id), MARKER)
    fun isInstalled(id: ArtifactId) = marker(id).isFile

    companion object {
        const val MARKER = "installed.json"
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
)

/**
 * One file transfer: HTTPS GET with Range resume from the .part length; every redirect target must match
 * CatalogValidator.allowedRedirectHosts; SHA-256 streamed while writing; fsync; atomic rename (WP-13).
 */
interface Downloader {
    suspend fun fetch(url: String, part: File, expectedBytes: Long, expectedSha256: String, onProgress: (Long) -> Unit): app.murmur.core.model.Outcome<File>
}

/** tar.bz2 / zip extraction with path-traversal protection (entries must stay inside the artifact dir). WP-13. */
interface ArchiveExtractor {
    fun extract(archive: File, intoDir: File, type: app.murmur.core.catalog.ArchiveType): app.murmur.core.model.Outcome<List<File>>
}

/** Bytes needed on disk during install: archive + extracted size (archives double the peak). */
fun peakInstallBytes(a: Artifact): Long = a.files.sumOf { if (it.archive != null) it.bytes * 2 else it.bytes }
