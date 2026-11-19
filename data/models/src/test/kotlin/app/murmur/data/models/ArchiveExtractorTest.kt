package app.murmur.data.models

import app.murmur.core.catalog.ArchiveType
import app.murmur.core.model.Outcome
import java.io.ByteArrayOutputStream
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crafted archives: the sherpa SM8750 tarball shape extracts; traversal, absolute names and links are rejected. */
class ArchiveExtractorTest {
    private val base = tempDir("extract")
    private val into = File(base, "models/artifact").also { it.mkdirs() }
    private val extractor = CommonsArchiveExtractor()

    private sealed interface E {
        data class F(val name: String, val data: ByteArray) : E
        data class D(val name: String) : E
        data class L(val name: String, val target: String, val hard: Boolean = false) : E
    }

    private fun tarBz2(vararg entries: E): File {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (e in entries) when (e) {
                is E.F -> { tar.putArchiveEntry(TarArchiveEntry(e.name, true).apply { size = e.data.size.toLong() }); tar.write(e.data); tar.closeArchiveEntry() }
                is E.D -> { tar.putArchiveEntry(TarArchiveEntry(e.name.trimEnd('/') + "/", true)); tar.closeArchiveEntry() }
                is E.L -> {
                    val t = TarArchiveEntry(e.name, if (e.hard) TarArchiveEntry.LF_LINK else TarArchiveEntry.LF_SYMLINK, true)
                    t.linkName = e.target
                    tar.putArchiveEntry(t); tar.closeArchiveEntry()
                }
            }
        }
        return File(into, "a.tar.bz2").also { it.writeBytes(bytes.toByteArray()) }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val f = File(into, "a.zip")
        ZipArchiveOutputStream(f).use { z ->
            for ((n, d) in entries) { z.putArchiveEntry(ZipArchiveEntry(n)); z.write(d); z.closeArchiveEntry() }
        }
        return f
    }

    @Test
    fun sherpaTarballShapeExtractsInPlace() {
        val enc = bytesOf(200_000)
        val a = tarBz2(
            E.D("sherpa-onnx-qnn-SM8750-binary-x/"),
            E.F("sherpa-onnx-qnn-SM8750-binary-x/encoder.bin", enc),
            E.F("sherpa-onnx-qnn-SM8750-binary-x/tokens.txt", "a 0\n".encodeToByteArray()),
            E.D("sherpa-onnx-qnn-SM8750-binary-x/test_wavs/"),
            E.F("sherpa-onnx-qnn-SM8750-binary-x/test_wavs/en.wav", bytesOf(100)),
        )
        val r = extractor.extract(a, into, ArchiveType.TAR_BZ2)
        assertTrue(r is Outcome.Ok)
        assertEquals(3, (r as Outcome.Ok).value.size)
        assertTrue(File(into, "sherpa-onnx-qnn-SM8750-binary-x/encoder.bin").readBytes().contentEquals(enc))
        assertTrue(into.walk().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun pathTraversalIsRejectedAndNothingLandsOutside() {
        val a = tarBz2(
            E.F("ok/first.txt", "fine".encodeToByteArray()),
            E.F("ok/../../escaped.txt", "evil".encodeToByteArray()),
        )
        val r = extractor.extract(a, into, ArchiveType.TAR_BZ2)
        assertTrue(r is Outcome.Err)
        assertTrue(((r as Outcome.Err).error.detail ?: "").contains("archive rejected"))
        assertFalse(File(base, "models/escaped.txt").exists())
        assertFalse(File(base, "escaped.txt").exists())
        assertFalse("files written before the bad entry are removed", File(into, "ok/first.txt").exists())
    }

    @Test
    fun absoluteNamesAreRejected() {
        val outside = File(base, "abs.txt")
        val a = tarBz2(E.F(outside.absolutePath, "evil".encodeToByteArray()))
        assertTrue(extractor.extract(a, into, ArchiveType.TAR_BZ2) is Outcome.Err)
        assertFalse(outside.exists())
    }

    @Test
    fun symlinkAndHardLinkEntriesAreRejected() {
        assertTrue(extractor.extract(tarBz2(E.L("ok/link", "/etc/passwd")), into, ArchiveType.TAR_BZ2) is Outcome.Err)
        assertTrue(extractor.extract(tarBz2(E.L("ok/hard", "../../x", hard = true)), into, ArchiveType.TAR_BZ2) is Outcome.Err)
        assertFalse(File(into, "ok/link").exists())
    }

    @Test
    fun zipTraversalIsRejectedAndPlainZipExtracts() {
        assertTrue(extractor.extract(zip("../zip-escape.txt" to "x".encodeToByteArray()), into, ArchiveType.ZIP) is Outcome.Err)
        assertFalse(File(base, "models/zip-escape.txt").exists())
        val r = extractor.extract(zip("model.onnx" to bytesOf(1000), "dir/tokens.txt" to "t".encodeToByteArray()), into, ArchiveType.ZIP)
        assertTrue(r is Outcome.Ok)
        assertTrue(File(into, "dir/tokens.txt").isFile)
    }
}
