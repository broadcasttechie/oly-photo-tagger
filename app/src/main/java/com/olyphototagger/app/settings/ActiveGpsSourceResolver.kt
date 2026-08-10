package com.olyphototagger.app.settings

/**
 * Decides which GPS source, if any, is actually usable right now — pulled out of the
 * I/O-heavy GeotagWorkflowViewModel.buildOrchestrator() so this decision is unit-testable
 * on its own, mirroring how PairFilter separates decision logic from I/O elsewhere in
 * this codebase.
 */
object ActiveGpsSourceResolver {

    /**
     * @param selected the persisted `activeGpsSource` setting — null before the user has
     *   ever explicitly chosen one, including every install that predates this feature.
     * @param hasDawarichConfig whether a Dawarich base URL + token are currently saved.
     * @return the source to actually use, or null if nothing usable is configured.
     */
    fun resolve(selected: GpsSourceType?, hasDawarichConfig: Boolean): GpsSourceType? = when (selected) {
        GpsSourceType.GPX -> GpsSourceType.GPX
        GpsSourceType.DAWARICH -> GpsSourceType.DAWARICH.takeIf { hasDawarichConfig }
        // Nothing explicitly chosen yet — auto-default to Dawarich if it's already
        // configured (every install from before this feature existed), so shipping this
        // doesn't silently break an existing working setup. Otherwise: nothing usable.
        null -> GpsSourceType.DAWARICH.takeIf { hasDawarichConfig }
    }
}
