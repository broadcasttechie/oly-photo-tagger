package com.olyphototagger.app.pipeline

import java.time.Instant

/**
 * Cluster-aware progress for [GeotagOrchestrator.fetchClusteredTrack] — composes a single
 * cluster's own [com.olyphototagger.app.geotag.FetchProgress] (page/totalPages *within
 * that cluster's* fetch) with which cluster it is and the date range it covers. Neither
 * [com.olyphototagger.app.geotag.GpsSource] nor [com.olyphototagger.app.geotag.FetchProgress]
 * has any notion of clusters, so this is where that context actually lives.
 *
 * [page]/[totalPages] are the real completed/total pair to project an ETA from (see
 * [com.olyphototagger.app.ui.workflow.estimateRemaining]) — [pointsSoFar] alone can't
 * support one, since the total *point* count for a cluster isn't known until its fetch
 * has already finished; page count is known as soon as the first response lands.
 * [clusterStartedAt] is when *this* cluster's own fetch began, not the whole
 * [GeotagOrchestrator.fetchClusteredTrack] call — an ETA needs the elapsed time this
 * cluster's own pages were actually fetched over, not time spent on earlier clusters.
 */
data class TrackFetchProgress(
    val pointsSoFar: Int,
    val page: Int,
    val totalPages: Int,
    val clusterIndex: Int,
    val clusterCount: Int,
    val rangeStart: Instant,
    val rangeEnd: Instant,
    val clusterStartedAt: Instant
)
