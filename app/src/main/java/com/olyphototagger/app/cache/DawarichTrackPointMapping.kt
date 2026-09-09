package com.olyphototagger.app.cache

import com.olyphototagger.app.geotag.TrackPoint
import java.time.Instant

fun DawarichTrackPointEntity.toTrackPoint(): TrackPoint =
    TrackPoint(
        time = Instant.ofEpochSecond(epochSeconds),
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters
    )

fun TrackPoint.toDawarichEntity(): DawarichTrackPointEntity =
    DawarichTrackPointEntity(
        epochSeconds = time.epochSecond,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters
    )
