package com.olyphototagger.app.image

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

/**
 * A manually-wired, process-wide Coil [ImageLoader] — same "one shared instance, built
 * lazily on first use" shape as [com.olyphototagger.app.cache.AppDatabase.getInstance],
 * used explicitly at each `AsyncImage` call site rather than via Coil's own
 * `SingletonImageLoader`, which would need this project to gain a custom `Application`
 * class it otherwise has no reason for (matches the rest of the codebase's no-DI-framework,
 * manual-wiring style).
 *
 * The OkHttp network fetcher is what lets an `AsyncImage` load an `https://` URL (the OSM
 * map-tile preview — see [osmTileUrl]) — a plain local `content://` SAF Uri (a photo
 * thumbnail) works without it, Coil's core already handles those.
 */
object ThumbnailImageLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components { add(OkHttpNetworkFetcherFactory()) }
                .build()
                .also { instance = it }
        }
}
