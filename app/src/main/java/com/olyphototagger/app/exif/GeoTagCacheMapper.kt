package com.olyphototagger.app.exif

import com.olyphototagger.app.cache.GeoTagCacheEntity
import java.time.Instant
import java.time.LocalDateTime

/**
 * Pure translation between [PhotoExifStatus]/[CaptureTimestamp] and the plain columns
 * [GeoTagCacheEntity] stores them as — kept separate from
 * [com.olyphototagger.app.pipeline.GeotagOrchestrator] so the mapping itself is
 * unit-testable without Room, matching [com.olyphototagger.app.write.WriteLogMapper]'s
 * reason for existing as its own object.
 */
object GeoTagCacheMapper {

    fun from(fileKey: String, status: PhotoExifStatus, checkedAt: Instant): GeoTagCacheEntity {
        val timestamp = status.captureTimestamp
        return GeoTagCacheEntity(
            fileKey = fileKey,
            hasGeoTag = status.hasGeoTag,
            checkedAtEpochMillis = checkedAt.toEpochMilli(),
            captureTimestampExactEpochMillis = (timestamp as? CaptureTimestamp.Exact)?.instant?.toEpochMilli(),
            captureTimestampNaiveLocal = (timestamp as? CaptureTimestamp.Naive)?.localDateTime?.toString()
        )
    }
}

/** The inverse of [GeoTagCacheMapper.from]'s timestamp encoding — see [GeoTagCacheEntity]'s
 *  own doc for why at most one of the two columns is ever non-null. */
fun GeoTagCacheEntity.toCaptureTimestamp(): CaptureTimestamp? = when {
    captureTimestampExactEpochMillis != null -> CaptureTimestamp.Exact(Instant.ofEpochMilli(captureTimestampExactEpochMillis))
    captureTimestampNaiveLocal != null -> CaptureTimestamp.Naive(LocalDateTime.parse(captureTimestampNaiveLocal))
    else -> null
}
