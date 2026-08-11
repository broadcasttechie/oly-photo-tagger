package com.olyphototagger.app.dcim

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
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
class DcimScanner(private val contentResolver: ContentResolver) {

    suspend fun scan(dcimRoot: DocumentFile): DcimScanResult = withContext(Dispatchers.IO) {
        val documentsByUri = mutableMapOf<String, DocumentFile>()
        val files = mutableListOf<CameraFile>()

        fun register(document: DocumentFile, folderName: String, meta: ChildMetadata) {
            documentsByUri[document.uri.toString()] = document
            files += CameraFile(
                uriString = document.uri.toString(),
                displayName = meta.displayName,
                folderName = folderName,
                sizeBytes = meta.size,
                lastModified = meta.lastModified.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
            )
        }

        val rootChildren = listChildrenWithMetadata(dcimRoot)

        rootChildren.filter { (_, meta) -> !meta.isDirectory }
            .forEach { (document, meta) -> register(document, folderName = "", meta) }

        rootChildren.filter { (_, meta) -> meta.isDirectory }
            .forEach { (folder, folderMeta) ->
                listChildrenWithMetadata(folder)
                    .filter { (_, meta) -> !meta.isDirectory }
                    .forEach { (document, meta) -> register(document, folderMeta.displayName, meta) }
            }

        DcimScanResult(files, documentsByUri)
    }

    /**
     * [DocumentFile.listFiles] itself is one cheap query (document IDs only), and its
     * results already have correctly-wired [DocumentFile.getParentFile] references — but
     * every metadata property accessed on those results afterward (isFile/isDirectory/
     * name/length/lastModified) is its *own* separate ContentResolver round trip. Confirmed
     * against androidx.documentfile 1.0.1's actual source: `TreeDocumentFile` delegates
     * every one of those to a helper that runs a fresh single-column query per call, per
     * document. At real camera-folder sizes (hundreds to 1000+ files) that's thousands of
     * individual IPC round trips — confirmed on-device: this was the actual cause of a
     * "cheap, brief" incomplete-write check taking over a minute at 1000 files, not
     * anything write-path-related.
     *
     * Fetches all the metadata for a folder's children in one extra bulk query instead,
     * keyed by document ID, and only falls back to the slow per-property path for a
     * document a same-listing bulk query somehow didn't cover, or for a non-SAF root
     * (`file://`, from [DocumentFile.fromFile] — used by this app's own JVM/instrumented
     * tests) — that path is already backed by plain `java.io.File` calls with no IPC cost,
     * so there's nothing to optimize there and no bulk-query API to use anyway.
     */
    private fun listChildrenWithMetadata(folder: DocumentFile): List<Pair<DocumentFile, ChildMetadata>> {
        val children = folder.listFiles()
        val metaById = if (folder.uri.scheme == "content") queryChildMetadata(folder.uri) else emptyMap()
        return children.mapNotNull { child ->
            val documentId = if (metaById.isEmpty()) null else runCatching { DocumentsContract.getDocumentId(child.uri) }.getOrNull()
            val meta = documentId?.let(metaById::get) ?: fallbackMetadata(child)
            meta?.let { child to it }
        }
    }

    private fun queryChildMetadata(folderUri: Uri): Map<String, ChildMetadata> {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, DocumentsContract.getDocumentId(folderUri))
        }.getOrNull() ?: return emptyMap()

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = mutableMapOf<String, ChildMetadata>()
        runCatching {
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    result[documentId] = ChildMetadata(
                        displayName = displayName,
                        isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = cursor.getLong(3),
                        lastModified = cursor.getLong(4)
                    )
                }
            }
        }
        return result
    }

    private fun fallbackMetadata(document: DocumentFile): ChildMetadata? {
        val displayName = document.name ?: return null
        return ChildMetadata(
            displayName = displayName,
            isDirectory = document.isDirectory,
            size = document.length(),
            lastModified = document.lastModified()
        )
    }

    private data class ChildMetadata(
        val displayName: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )
}
