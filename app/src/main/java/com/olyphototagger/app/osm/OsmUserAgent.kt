package com.olyphototagger.app.osm

import com.olyphototagger.app.BuildConfig

/**
 * Both the map tile server (`tile.openstreetmap.org`, see [com.olyphototagger.app.image.
 * osmTileUrl]) and Nominatim (`nominatim.openstreetmap.org`, see [com.olyphototagger.app.
 * geocode.NominatimClient]) are OSM Foundation services with their own usage policy, and
 * both require a valid HTTP User-Agent identifying the calling application — this is that
 * identification, shared so both call sites say the same thing rather than drifting.
 */
const val OSM_USER_AGENT = "OlyPhotoTagger/${BuildConfig.VERSION_NAME} (+https://github.com/broadcasttechie/oly-photo-tagger)"
