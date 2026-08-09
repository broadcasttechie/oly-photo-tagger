package com.olyphototagger.app.dcim

import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Walks a camera volume's DCIM tree, granted via the Storage Access Framework, into the
 * flat file listing [PhotoPairer] groups into RAW+JPEG pairs.
 */
class DcimScanner {

    suspend fun scan(dcimRoot: DocumentFile): List<CameraFile> = withContext(Dispatchers.IO) {
        val directChildren = dcimRoot.listFiles()

        val filesDirectlyInRoot = directChildren
            .filter { it.isFile }
            .mapNotNull { it.toCameraFile(folderName = "") }

        val filesInSubfolders = directChildren
            .filter { it.isDirectory }
            .flatMap { folder ->
                val folderName = folder.name.orEmpty()
                folder.listFiles()
                    .filter { it.isFile }
                    .mapNotNull { it.toCameraFile(folderName) }
            }

        filesDirectlyInRoot + filesInSubfolders
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
