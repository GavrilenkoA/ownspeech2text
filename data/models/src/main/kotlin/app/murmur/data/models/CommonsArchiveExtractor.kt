package app.murmur.data.models

import app.murmur.core.catalog.ArchiveType
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * tar.bz2 / zip extraction (commons-compress, ADR-017) that never writes outside [intoDir]:
 *  - every entry name is normalised; absolute names, `..` segments and names that resolve outside the directory
 *    reject the whole archive (nothing it already wrote is kept);
 *  - symbolic and hard links, device and FIFO entries are rejected (model archives contain plain files only);
 *  - a file is written to `<name>.tmp` and renamed when complete, so a killed extraction leaves no torn files.
 * Streaming: no temporary copy of the archive, memory is one buffer.
 */
class CommonsArchiveExtractor(private val log: DlLog = DlLog.NONE) : ArchiveExtractor {

    override fun extract(archive: File, intoDir: File, type: ArchiveType): Outcome<List<File>> {
        val root = intoDir.canonicalFile
        val written = mutableListOf<File>()
        var tmp: File? = null
        return try {
            open(archive, type).use { input ->
                while (true) {
                    val entry: ArchiveEntry = input.nextEntry ?: break
                    if (!input.canReadEntryData(entry)) throw Rejected("unreadable entry")
                    checkKind(entry)
                    val target = resolveInside(root, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    val t = File(target.parentFile, target.name + ".tmp").also { tmp = it }
                    FileOutputStream(t).use { out ->
                        input.copyTo(out, 256 * 1024)
                        out.fd.sync()
                    }
                    if (!t.renameTo(target)) throw IOException("rename failed")
                    tmp = null
                    written += target
                }
            }
            log.event("extract_done", "archive" to archive.name, "files" to written.size)
            Outcome.Ok(written)
        } catch (e: Rejected) {
            written.forEach { it.delete() }
            tmp?.delete()
            log.event("extract_rejected", "archive" to archive.name, "reason" to e.message)
            Outcome.Err(MurmurError.DownloadFailed(null, "archive rejected: ${e.message}"))
        } catch (e: IOException) {
            written.forEach { it.delete() }
            tmp?.delete()
            log.event("extract_io", "archive" to archive.name, "err" to e.javaClass.simpleName, "noSpace" to isNoSpace(e))
            Outcome.Err(diskError(e, "extract"))
        }
    }

    private fun open(archive: File, type: ArchiveType): ArchiveInputStream<*> {
        val raw: InputStream = BufferedInputStream(FileInputStream(archive), 1 shl 16)
        return when (type) {
            ArchiveType.TAR_BZ2 -> TarArchiveInputStream(BZip2CompressorInputStream(raw, true))
            ArchiveType.ZIP -> ZipArchiveInputStream(raw)
        }
    }

    private fun checkKind(e: ArchiveEntry) {
        when (e) {
            is TarArchiveEntry -> if (e.isSymbolicLink || e.isLink || e.isCharacterDevice || e.isBlockDevice || e.isFIFO)
                throw Rejected("link or special entry")
            is ZipArchiveEntry -> if (e.isUnixSymlink) throw Rejected("link entry")
        }
    }

    private class Rejected(msg: String) : Exception(msg)

    companion object {
        /** The file an entry name maps to, or [Rejected] when it would leave [root]. */
        private fun resolveInside(root: File, name: String): File {
            val clean = name.replace('\\', '/')
            if (clean.isEmpty() || clean.startsWith("/") || clean.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(clean))
                throw Rejected("absolute name")
            val parts = clean.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty()) throw Rejected("empty name")
            if (parts.any { it == ".." }) throw Rejected("parent segment")
            val target = File(root, parts.joinToString(File.separator)).canonicalFile
            if (!target.path.startsWith(root.path + File.separator)) throw Rejected("outside target")
            return target
        }
    }
}
