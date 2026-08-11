package com.olyphototagger.app.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per file-level write *attempt* (JPEG and RAW each get their own row, even
 * though they're written together as a pair) — not just successes. An audit log that only
 * recorded [WriteLogResultType.WRITTEN] couldn't answer "why wasn't this photo tagged",
 * which is exactly the question it exists to answer.
 *
 * A single flat table with nullable columns, discriminated by [resultType], rather than one
 * table per [com.olyphototagger.app.write.GpsExifWriteResult] variant — Room has no real
 * support for polymorphic entities, and this is meant to be read back as a simple
 * chronological list, not queried per-variant. [previousLatitude]/[previousLongitude] cover
 * both "what was already there" cases ([WriteLogResultType.WRITTEN]'s prior tag,
 * [WriteLogResultType.SKIPPED_ALREADY_TAGGED]'s existing tag) since they're the same kind of
 * value; everything else variant-specific (failure reason, mime type, temp/backup names)
 * goes in [detail] as a short human-readable string built at insert time — see
 * [com.olyphototagger.app.write.WriteLogMapper].
 */
@Entity(tableName = "write_log")
data class WriteLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loggedAtEpochMillis: Long,
    val folderName: String,
    val displayName: String,
    val resultType: String,
    val previousLatitude: Double? = null,
    val previousLongitude: Double? = null,
    val newLatitude: Double? = null,
    val newLongitude: Double? = null,
    val newAltitudeMeters: Double? = null,
    val detail: String? = null
)

/** [WriteLogEntity.resultType]'s valid values — kept as an enum for [WriteLogMapper] to
 *  build from exhaustively, stored as its [name] since Room needs a primitive column type. */
enum class WriteLogResultType {
    WRITTEN, SKIPPED_ALREADY_TAGGED, UNSUPPORTED_FORMAT, FAILED, BACKUP_ARTIFACT_PRESENT, NEEDS_RECOVERY
}
