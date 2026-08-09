package com.olyphototagger.app.exiftool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class TarReaderTest {

    private fun tarHeader(name: String, size: Int, typeFlag: Char): ByteArray {
        val header = ByteArray(512)
        fun putString(offset: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            System.arraycopy(bytes, 0, header, offset, bytes.size)
        }
        putString(0, name)
        putString(124, String.format("%011o", size) + " ")
        header[156] = typeFlag.code.toByte()
        return header
    }

    private fun pad(bytes: ByteArray): ByteArray {
        val remainder = bytes.size % 512
        if (remainder == 0) return bytes
        return bytes + ByteArray(512 - remainder)
    }

    private fun tarFileEntry(name: String, content: String): ByteArray {
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        return tarHeader(name, contentBytes.size, '0') + pad(contentBytes)
    }

    private fun tarDirEntry(name: String): ByteArray =
        tarHeader(if (name.endsWith("/")) name else "$name/", 0, '5')

    private fun tempDir(): File =
        File.createTempFile("tarreader", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }

    @Test
    fun `extracts a regular file with correct content`() {
        val archive = tarFileEntry("hello.txt", "hello world")
        val dest = tempDir()

        TarReader.extract(ByteArrayInputStream(archive), dest)

        val extracted = File(dest, "hello.txt")
        assertTrue(extracted.exists())
        assertEquals("hello world", extracted.readText())
    }

    @Test
    fun `extracts nested directories and files`() {
        val archive = tarDirEntry("lib") + tarFileEntry("lib/Carp.pm", "package Carp;")
        val dest = tempDir()

        TarReader.extract(ByteArrayInputStream(archive), dest)

        assertTrue(File(dest, "lib").isDirectory)
        assertEquals("package Carp;", File(dest, "lib/Carp.pm").readText())
    }

    @Test(expected = IOException::class)
    fun `rejects path traversal via dot-dot`() {
        val archive = tarFileEntry("../../etc/evil.txt", "pwned")
        TarReader.extract(ByteArrayInputStream(archive), tempDir())
    }

    @Test
    fun `absolute path is treated as relative to dest, not escaping it`() {
        val archive = tarFileEntry("/etc/passwd", "not actually escaping")
        val dest = tempDir()

        TarReader.extract(ByteArrayInputStream(archive), dest)

        assertTrue(File(dest, "etc/passwd").exists())
    }

    @Test(expected = IOException::class)
    fun `rejects entry exceeding the max size cap`() {
        val header = tarHeader("huge.bin", 65 * 1024 * 1024, '0')
        TarReader.extract(ByteArrayInputStream(header), tempDir())
    }

    @Test
    fun `non-regular entries are skipped without being materialized`() {
        // '2' = symlink type flag; we don't ship any, so these should be silently skipped.
        val archive = tarHeader("suspicious-link", 0, '2') + tarFileEntry("real.txt", "kept")
        val dest = tempDir()

        TarReader.extract(ByteArrayInputStream(archive), dest)

        assertFalse(File(dest, "suspicious-link").exists())
        assertEquals("kept", File(dest, "real.txt").readText())
    }

    @Test
    fun `empty archive produces an empty but existing destination`() {
        val dest = tempDir()

        TarReader.extract(ByteArrayInputStream(ByteArray(0)), dest)

        assertTrue(dest.exists())
        assertEquals(0, dest.listFiles()?.size ?: 0)
    }
}
