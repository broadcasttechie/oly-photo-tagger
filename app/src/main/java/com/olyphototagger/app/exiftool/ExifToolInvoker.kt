package com.olyphototagger.app.exiftool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

/**
 * Runs exiftool (perl shipped via jniLibs) against a real filesystem path — perl can't
 * read content:// SAF URIs, so the caller must stage the file to a real path first.
 *
 * Adapted from bestvibes/exiftoolwrapper-android's ExifToolRunner (MIT — see
 * native/NOTICE), narrowed to this app's one fixed invocation shape. The original
 * exposes a free-form user-typed exiftool command and needs a flag denylist to defend
 * against it; nothing here ever passes user-typed text into the argv, so that entire
 * defense layer doesn't apply — see [GpsExifToolCommand].
 *
 * Argv layout:
 *
 *     ${nativeLibraryDir}/libperl.so   # the perl interpreter, exec mount
 *     -I ${filesDir}/perl5/arch        # XS module symlinks (AssetExtractor wires these)
 *     -I ${filesDir}/perl5/lib         # exiftool's bundled lib tree
 *     ${filesDir}/perl5/exiftool       # the exiftool script
 *     <GpsExifToolCommand.build(...)>  # fixed GPS-write flags + target path
 *
 * No shell is involved — ProcessBuilder takes a List<String> directly.
 */
class ExifToolInvoker(private val context: Context) {

    data class Result(val exitCode: Int, val output: String) {
        val succeeded: Boolean get() = exitCode == 0
    }

    private fun perlBinary(): String = "${context.applicationInfo.nativeLibraryDir}/libperl.so"

    fun isInstalled(): Boolean =
        File(perlBinary()).exists() && AssetExtractor.isInstalled(context)

    suspend fun ensureInstalled() = AssetExtractor.ensureInstalled(context)

    suspend fun run(args: List<String>): Result = withContext(Dispatchers.IO) {
        val perl5 = AssetExtractor.perl5Dir(context).absolutePath
        val command = buildList {
            add(perlBinary())
            add("-I"); add("$perl5/arch")
            add("-I"); add("$perl5/lib")
            add(AssetExtractor.exiftoolScript(context).absolutePath)
            addAll(args)
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val exitCode = process.waitFor()
        Result(exitCode, output)
    }
}
