package com.olyphototagger.app.dcim

import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * [files] is the pure CameraFile listing; [resolve] maps a CameraFile back to the real
 * DocumentFile that produced it — needed later to actually write to the file (SAF write
 * operations, and reliable parent-folder access for the temp-file safety sequence, need a
 * DocumentFile obtained by genuine tree traversal, not reconstructed from a bare URI).
 *
 * Kept separate from CameraFile itself deliberately: CameraFile holds a plain uriString
 * rather than a DocumentFile specifically so the domain model and PhotoPairer stay JVM
 * unit-testable without Robolectric. This class is the (untested, Android-glue) bridge
 * back to real DocumentFile objects for the one caller — GeotagOrchestrator — that
 * actually needs to write.
 */
class DcimScanResult(
    val files: List<CameraFile>,
    private val documentsByUri: Map<String, DocumentFile>
) {
    fun resolve(file: CameraFile): DocumentFile? = documentsByUri[file.uriString]
}

/**
 * Walks a camera volume's DCIM tree, granted via the Storage Access Framework, into the
 * flat file listing [PhotoPairer] groups into RAW+JPEG pairs.
 */
class DcimScanner {

    suspend fun scan(dcimRoot: DocumentFile): DcimScanResult = withContext(Dispatchers.IO) {
        val documentsByUri = mutableMapOf<String, DocumentFile>()
        val files = mutableListOf<CameraFile>()

        fun register(document: DocumentFile, folderName: String) {
            val cameraFile = document.toCameraFile(folderName) ?: return
            documentsByUri[cameraFile.uriString] = document
            files += cameraFile
        }

        val directChildren = dcimRoot.listFiles()

        directChildren.filter { it.isFile }.forEach { register(it, folderName = "") }

        directChildren.filter { it.isDirectory }.forEach { folder ->
            val folderName = folder.name.orEmpty()
            folder.listFiles().filter { it.isFile }.forEach { register(it, folderName) }
        }

        DcimScanResult(files, documentsByUri)
    }

    private fun DocumentFile.toCameraFile(folderName: String): CameraFile? {
        val displayName = name ?: return null
        return CameraFile(
            uriString = uri.toString(),
            displayName = displayName,
            folderName = folderName,
            sizeBytes = length(),
            lastModified = lastModified().takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        )
    }
}
