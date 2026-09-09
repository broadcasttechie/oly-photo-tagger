package com.olyphototagger.app.geocode

import android.content.Context
import android.util.Log
import com.olyphototagger.app.cache.AddressCacheDao
import com.olyphototagger.app.cache.AddressCacheEntity
import com.olyphototagger.app.cache.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves a coordinate to a human-readable address, cached (see [bucketKey]) and rate-
 * limited to Nominatim's real usage-policy ceiling of one request per second — see
 * [NominatimClient]'s own doc for why that enforcement lives here, not there.
 *
 * A cache *hit* never touches [rateLimiterMutex] at all — only an actual network-bound
 * miss contends on it, so the common case (an already-resolved bucket, which after the
 * first scan of any given area is almost everything) stays a plain, fast, uncontended
 * Room read. [minIntervalMillis] defaults a little over Nominatim's stated 1 req/s, not
 * exactly at it — real headroom against clock/measurement slop mattering more than
 * shaving 100ms off an already-background operation.
 *
 * Deliberately called from each dry-run row's own composition-scoped coroutine (see
 * [com.olyphototagger.app.ui.workflow.rememberAddress]) rather than eagerly for a whole
 * scan result up front: a row that scrolls off-screen before its own lookup's turn comes
 * up has that lookup cancelled for free (LaunchedEffect leaving composition cancels its
 * coroutine, and a coroutine suspended waiting on [rateLimiterMutex] is a real cancellable
 * suspension point, not a blocked thread) — never wasting a slow, rate-limited request on
 * something no longer visible. Every suspension point here (the mutex, [delay], the
 * network call itself) is cooperative, not thread-blocking, so this can never itself be
 * the cause of "lag" anywhere else in the app, however many rows are waiting on it at once.
 */
class AddressResolver(
    private val dao: AddressCacheDao,
    private val client: NominatimClient,
    private val minIntervalMillis: Long = 1_100L,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val rateLimiterMutex = Mutex()
    // Null, not 0L, specifically so the very first lookup this process ever makes never
    // waits at all — only the gap *between* two real requests is rate-limited.
    private var lastRequestAtMs: Long? = null

    suspend fun resolve(latitude: Double, longitude: Double): String? {
        val key = bucketKey(latitude, longitude)
        dao.find(key)?.let { return it.address }

        return rateLimiterMutex.withLock {
            // Another row may have resolved this exact bucket while this one was waiting
            // for the lock — re-check rather than paying for a second real request.
            dao.find(key)?.let { return@withLock it.address }

            val last = lastRequestAtMs
            val waitMs = if (last == null) 0L else minIntervalMillis - (now() - last)
            if (waitMs > 0) delay(waitMs)
            lastRequestAtMs = now()

            val address = runCatching { client.reverseGeocode(latitude, longitude) }.getOrNull()
            if (address != null) {
                dao.insert(AddressCacheEntity(key, address))
            }
            address
        }
    }

    companion object {
        @Volatile private var instance: AddressResolver? = null

        fun get(context: Context): AddressResolver =
            instance ?: synchronized(this) {
                instance ?: AddressResolver(
                    AppDatabase.getInstance(context).addressCacheDao(),
                    NominatimClient(createNominatimHttpClient(), log = { message -> Log.d("NominatimClient", message) })
                ).also { instance = it }
            }
    }
}
