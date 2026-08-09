package com.olyphototagger.app.exiftool

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal POSIX/USTAR tar extractor for assets/perl5.tar.
 *
 * Adapted from bestvibes/exiftoolwrapper-android (MIT License) — see native/NOTICE.
 * Ported as-is: this is generic, project-independent extraction logic.
 *
 * We control the producer (native/build.sh emits a deterministic POSIX tar with no
 * extended headers, no symlinks, only regular files and directories). That lets us
 * skip the dependency on commons-compress / jarchivelib entirely and keep the
 * extraction surface small enough to audit.
 *
 * Hardening:
 *  - Reject absolute paths and any path that escapes the destination via "..".
 *  - Skip non-regular-file entries (symlinks, devices, etc. — we don't ship any).
 *  - Cap a single entry at 64 MiB (anything larger is a producer bug).
 */
object TarReader {

    private const val BLOCK = 512
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    @Throws(IOException::class)
    fun extract(input: InputStream, destDir: File) {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IOException("Could not create $destDir")
        }
        val canonicalDest = destDir.canonicalFile

        val header = ByteArray(BLOCK)
        while (true) {
            val read = readFully(input, header)
            if (read == 0) return // clean EOF
            if (read < BLOCK) throw IOException("Truncated tar header")
            if (header.all { it == 0.toByte() }) {
                // Two consecutive zero blocks mark end of archive; we accept the first.
                return
            }

            val nameField = readString(header, 0, 100)
            val prefixField = readString(header, 345, 155)
            val rawName = if (prefixField.isNotEmpty()) "$prefixField/$nameField" else nameField
            // Strip any leading slashes — matches GNU tar's default behavior, and ensures
            // File(destDir, name) treats name as relative regardless of producer mistakes.
            val name = rawName.trimStart('/')
            if (name.isEmpty() || name.contains(" ")) {
                throw IOException("Tar entry has empty or invalid name")
            }

            val sizeField = readString(header, 124, 12).trim()
            val size = if (sizeField.isEmpty()) 0L else sizeField.toLong(8)
            if (size < 0 || size > MAX_ENTRY_BYTES) {
                throw IOException("Refusing tar entry with size=$size: $name")
            }

            val typeFlag = header[156].toInt().toChar()

            val target = File(canonicalDest, name).canonicalFile
            if (!target.path.startsWith(canonicalDest.path + File.separator) &&
                target.path != canonicalDest.path
            ) {
                throw IOException("Tar entry '$name' would escape destination")
            }

            when (typeFlag) {
                '5' -> { // directory
                    if (!target.exists() && !target.mkdirs()) {
                        throw IOException("Could not create directory $target")
                    }
                    skipPadding(input, size)
                }
                '0', ' ' -> { // regular file
                    target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    target.outputStream().use { out -> copy(input, out, size) }
                    skipPadding(input, size)
                }
                else -> {
                    // Unsupported entry type (symlink, hardlink, char/block device, etc.).
                    // Skip the data so we stay aligned, but don't materialize anything.
                    skipExactly(input, size)
                    skipPadding(input, size)
                }
            }
        }
    }

    private fun readString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) return total
            total += n
        }
        return total
    }

    private fun copy(input: InputStream, out: OutputStream, bytes: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = bytes
        while (remaining > 0) {
            val want = if (remaining < buf.size) remaining.toInt() else buf.size
            val n = input.read(buf, 0, want)
            if (n < 0) throw IOException("Truncated tar payload, $remaining bytes remaining")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipExactly(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val want = if (remaining < buf.size) remaining.toInt() else buf.size
            val n = input.read(buf, 0, want)
            if (n < 0) throw IOException("Truncated tar payload while skipping")
            remaining -= n
        }
    }

    private fun skipPadding(input: InputStream, payload: Long) {
        val rem = (payload % BLOCK).toInt()
        if (rem == 0) return
        skipExactly(input, (BLOCK - rem).toLong())
    }
}
