package com.olyphototagger.app.write

import com.olyphototagger.app.cache.WriteLogEntity
import com.olyphototagger.app.cache.WriteLogResultType
import java.time.Instant

/**
 * Pure translation from one file's [GpsExifWriteResult] into a [WriteLogEntity] row — kept
 * separate from [com.olyphototagger.app.pipeline.GeotagOrchestrator] (which calls this once
 * per file write, success or not) so the mapping itself is unit-testable without Room or a
 * real write.
 */
object WriteLogMapper {

    fun from(folderName: String, displayName: String, result: GpsExifWriteResult, loggedAt: Instant): WriteLogEntity {
        val base = WriteLogEntity(
            loggedAtEpochMillis = loggedAt.toEpochMilli(),
            folderName = folderName,
            displayName = displayName,
            resultType = resultType(result).name
        )
        return when (result) {
            is GpsExifWriteResult.Written -> base.copy(
                previousLatitude = result.previousLatLong?.first,
                previousLongitude = result.previousLatLong?.second,
                newLatitude = result.newLatitude,
                newLongitude = result.newLongitude,
                newAltitudeMeters = result.newAltitudeMeters,
                detail = result.strayBackupFileName?.let { "Backup not yet cleaned up: $it" }
            )
            is GpsExifWriteResult.SkippedAlreadyTagged -> base.copy(
                previousLatitude = result.existingLatitude,
                previousLongitude = result.existingLongitude
            )
            is GpsExifWriteResult.UnsupportedFormat -> base.copy(
                detail = "Unsupported format" + (result.mimeType?.let { " ($it)" } ?: "")
            )
            is GpsExifWriteResult.Failed -> base.copy(detail = result.reason)
            is GpsExifWriteResult.BackupArtifactPresent -> base.copy(
                detail = "Backup present from an earlier interrupted write: ${result.backupFileName}"
            )
            is GpsExifWriteResult.NeedsRecovery -> base.copy(
                detail = "Interrupted mid-write — temp: ${result.tempFileName}, backup: ${result.backupFileName}"
            )
        }
    }

    private fun resultType(result: GpsExifWriteResult): WriteLogResultType = when (result) {
        is GpsExifWriteResult.Written -> WriteLogResultType.WRITTEN
        is GpsExifWriteResult.SkippedAlreadyTagged -> WriteLogResultType.SKIPPED_ALREADY_TAGGED
        is GpsExifWriteResult.UnsupportedFormat -> WriteLogResultType.UNSUPPORTED_FORMAT
        is GpsExifWriteResult.Failed -> WriteLogResultType.FAILED
        is GpsExifWriteResult.BackupArtifactPresent -> WriteLogResultType.BACKUP_ARTIFACT_PRESENT
        is GpsExifWriteResult.NeedsRecovery -> WriteLogResultType.NEEDS_RECOVERY
    }
}
