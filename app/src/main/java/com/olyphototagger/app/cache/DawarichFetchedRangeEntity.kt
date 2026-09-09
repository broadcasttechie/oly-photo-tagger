package com.olyphototagger.app.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records that every point Dawarich has for `[startEpochSeconds, endEpochSeconds]` is
 * already sitting in [DawarichTrackPointEntity] — so a later request whose own range
 * falls entirely inside an existing one of these can be served from the cache with no
 * network call at all (see `DawarichCacheDao.findCoveringRange` /
 * `com.olyphototagger.app.dawarich.CachingDawarichSource`).
 *
 * Deliberately a simple containment check, not general interval merging: two
 * overlapping-but-not-identical ranges are allowed to coexist rather than being merged or
 * split. That's a little redundant in the rare case a scan's computed clusters shift
 * slightly between runs, but never wrong — the alternative (merging/splitting ranges to
 * keep this table minimal) is real added complexity for a cache that's already disposable
 * and rebuildable by design.
 */
@Entity(tableName = "dawarich_fetched_range")
data class DawarichFetchedRangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long
)
