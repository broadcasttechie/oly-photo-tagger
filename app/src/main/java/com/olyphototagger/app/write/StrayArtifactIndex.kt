package com.olyphototagger.app.write

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.IdentityHashMap

/**
 * A per-batch cache of which folders currently contain stray `.tmp`/`.bak` write artifacts,
 * keyed by each artifact's recovered original name. [GpsExifWriter.write] needs an answer to
 * this on every call — a stray backup means refuse; a stray temp needs cleaning up first —
 * and scanning the parent folder fresh each time is the same SAF `listFiles()`-plus-per-child
 * `.name` N+1 cost that made `DcimScanner` take minutes at real folder sizes (see its own
 * doc): every property read on a `listFiles()` result is its own `ContentResolver` round
 * trip. Doing that once per *write* rather than once per *batch* multiplies it by the batch
 * size too — confirmed as the actual cause of a 200-photo write batch visibly decelerating
 * over its own run (2026-08-11 perf investigation), not anything about exiftool itself.
 *
 * Safe to build once per parent folder and reuse for a whole batch: within one
 * [com.olyphototagger.app.pipeline.GeotagOrchestrator.applyMatches] batch, every file is
 * written at most once, under its own distinct `.tmp`/`.bak` names, so no write in the batch
 * can create an artifact another write in the *same* batch needs to see — only artifacts
 * already on disk before the batch started (e.g. left by an earlier crashed run) are
 * relevant here, and those are exactly what a start-of-batch snapshot captures. Builds
 * lazily per distinct parent the first time that folder is actually needed, rather than
 * eagerly for every folder a batch might touch.
 *
 * [contentResolver] is nullable purely as a test seam: it's only ever dereferenced for a
 * `content://` parent (see [buildIndex]), so a JVM unit test exercising the `file://`-backed
 * `DocumentFile.fromFile()` path (same technique as [SafeFileSwap]'s own tests) can pass
 * `null` and never hit it — real callers ([GpsExifWriter.newStrayArtifactIndex]) always have
 * a real one and always pass it. Deliberately never calls [DocumentFile.getUri] unless a
 * resolver is actually present to use it with: `RawDocumentFile.getUri()` (what
 * `DocumentFile.fromFile()` produces) calls the real `android.net.Uri.fromFile`, which — like
 * any other unstubbed Android framework call — throws in a plain JVM unit test.
 */
class StrayArtifactIndex(private val contentResolver: ContentResolver?) {

    private val mutex = Mutex()
    // Identity, not value, keying: two DocumentFile instances for the "same" folder aren't
    // interchangeable here (nor does DocumentFile define value equality at all) — what
    // matters is that every write in one batch for files under the same folder shares the
    // exact same parent DocumentFile instance, which DcimScanner's single listFiles() call
    // per folder already guarantees.
    private val byParent = IdentityHashMap<DocumentFile, Map<String, List<Artifact>>>()

    private data class Artifact(val document: DocumentFile, val isTemp: Boolean)

    /** Any `.tmp` artifacts in [parent] that recover back to [originalName] — normally none. */
    suspend fun strayTemps(parent: DocumentFile, originalName: String): List<DocumentFile> =
        artifactsFor(parent, originalName).filter { it.isTemp }.map { it.document }

    /** True if [parent] contains a `.bak` artifact recovering back to [originalName]. */
    suspend fun hasStrayBackup(parent: DocumentFile, originalName: String): Boolean =
        artifactsFor(parent, originalName).any { !it.isTemp }

    private suspend fun artifactsFor(parent: DocumentFile, originalName: String): List<Artifact> =
        indexFor(parent)[originalName.lowercase()].orEmpty()

    private suspend fun indexFor(parent: DocumentFile): Map<String, List<Artifact>> = mutex.withLock {
        byParent.getOrPut(parent) { buildIndex(parent) }
    }

    /**
     * One bulk metadata query per folder (matching [com.olyphototagger.app.dcim.DcimScanner]
     * .listChildrenWithMetadata's technique) instead of a per-child `.name` read, then a
     * single local pass classifying whichever names actually parse as artifacts. Falls back
     * to the plain per-child path when there's no [contentResolver] to bulk-query with (a
     * non-`content://` root — `DocumentFile.fromFile()`, used by this app's own JVM tests —
     * is backed directly by `java.io.File` with no IPC cost, so there's nothing to bulk-fetch
     * there anyway).
     */
    private fun buildIndex(parent: DocumentFile): Map<String, List<Artifact>> {
        val children = parent.listFiles()
        val namesById = contentResolver?.let { resolver -> queryChildNames(resolver, parent.uri) }.orEmpty()

        return children.mapNotNull { child ->
            val name = resolveName(child, namesById) ?: return@mapNotNull null
            val artifact = GpsWriteSupport.parseArtifactName(name) ?: return@mapNotNull null
            artifact.recoveredName.lowercase() to Artifact(child, artifact.isTemp)
        }.groupBy({ it.first }, { it.second })
    }

    private fun resolveName(child: DocumentFile, namesById: Map<String, String>): String? {
        if (namesById.isEmpty()) return child.name
        val documentId = runCatching { DocumentsContract.getDocumentId(child.uri) }.getOrNull()
        return documentId?.let(namesById::get) ?: child.name
    }

    private fun queryChildNames(resolver: ContentResolver, folderUri: Uri): Map<String, String> {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, DocumentsContract.getDocumentId(folderUri))
        }.getOrNull() ?: return emptyMap()

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        val result = mutableMapOf<String, String>()
        runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    result[documentId] = displayName
                }
            }
        }
        return result
    }
}
