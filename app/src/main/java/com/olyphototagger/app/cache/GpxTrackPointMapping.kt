package com.olyphototagger.app.cache

import com.olyphototagger.app.geotag.TrackPoint
import java.time.Instant

fun GpxTrackPointEntity.toTrackPoint(): TrackPoint =
    TrackPoint(
        time = Instant.ofEpochSecond(epochSeconds),
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters
    )

fun TrackPoint.toEntity(importedFileId: Long): GpxTrackPointEntity =
    GpxTrackPointEntity(
        importedFileId = importedFileId,
        epochSeconds = time.epochSecond,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters
    )
