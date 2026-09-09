package com.olyphototagger.app.dawarich

import com.olyphototagger.app.cache.DawarichCacheDao
import com.olyphototagger.app.cache.DawarichFetchedRangeEntity
import com.olyphototagger.app.cache.DawarichTrackPointEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import com.olyphototagger.app.geotag.FetchProgress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class CachingDawarichSourceTest {

    private class FakeDawarichCacheDao : DawarichCacheDao {
        val fetchedRanges = mutableListOf<DawarichFetchedRangeEntity>()
        val points = mutableListOf<DawarichTrackPointEntity>()

        override suspend fun findCoveringRange(startEpochSeconds: Long, endEpochSeconds: Long): DawarichFetchedRangeEntity? =
            fetchedRanges.firstOrNull { it.startEpochSeconds <= startEpochSeconds && it.endEpochSeconds >= endEpochSeconds }

        override suspend fun pointsInRange(startEpochSeconds: Long, endEpochSeconds: Long): List<DawarichTrackPointEntity> =
            points.filter { it.epochSeconds in startEpochSeconds..endEpochSeconds }.sortedBy { it.epochSeconds }

        override suspend fun insertPoints(points: List<DawarichTrackPointEntity>) {
            this.points += points
        }

        override suspend fun insertFetchedRange(range: DawarichFetchedRangeEntity) {
            fetchedRanges += range
        }

        override suspend fun clearPoints() = points.clear()
        override suspend fun clearFetchedRanges() = fetchedRanges.clear()
    }

    private fun jsonResponse(engine: MockRequestHandleScope, body: String) =
        engine.respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type" to listOf("application/json"), "X-Total-Pages" to listOf("1"))
        )

    /** [requestCount] lets a test assert the network was (or wasn't) actually hit, not
     *  just that the returned data looks right — a cache bug that accidentally still
     *  fetches every time would otherwise be invisible to an assertion on points alone. */
    private fun countingClient(requestCount: AtomicInteger, body: String): DawarichClient {
        val engine = MockEngine {
            requestCount.incrementAndGet()
            jsonResponse(this, body)
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return DawarichClient(httpClient, "https://dawarich.example", "token")
    }

    /** Every test below fetches ranges in 1970 (small epoch-second values), so the real
     *  [Instant.now] default here is "old enough" regardless of when the test actually
     *  runs — recency-specific tests override [now] explicitly instead. */
    private fun cachingSource(
        client: DawarichClient,
        dao: DawarichCacheDao,
        recentWindow: Duration = Duration.ofHours(24),
        now: () -> Instant = Instant::now
    ) = CachingDawarichSource(client, dao, recentWindow, now)

    private val onePoint = """[{"latitude":"1.0","longitude":"1.0","timestamp":100}]"""

    @Test
    fun `a cold request hits the network and caches the result`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeDawarichCacheDao()
        val source = cachingSource(countingClient(requestCount, onePoint), dao)

        val points = source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000))

        assertEquals(1, requestCount.get())
        assertEquals(1, points.size)
        assertEquals(1, dao.fetchedRanges.size)
        assertEquals(1, dao.points.size)
    }

    @Test
    fun `an identical repeat request is served from cache, no second network call`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeDawarichCacheDao()
        val source = cachingSource(countingClient(requestCount, onePoint), dao)

        source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000))
        val second = source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000))

        assertEquals(1, requestCount.get())
        assertEquals(1, second.size)
    }

    @Test
    fun `a request fully inside an already-cached range is also served from cache`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeDawarichCacheDao()
        val source = cachingSource(countingClient(requestCount, onePoint), dao)

        source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000))
        source.fetchTrackPoints(Instant.ofEpochSecond(100), Instant.ofEpochSecond(900))

        assertEquals(1, requestCount.get())
    }

    @Test
    fun `a request outside the cached range still hits the network`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeDawarichCacheDao()
        val source = cachingSource(countingClient(requestCount, onePoint), dao)

        source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000))
        source.fetchTrackPoints(Instant.ofEpochSecond(5000), Instant.ofEpochSecond(6000))

        assertEquals(2, requestCount.get())
        assertEquals(2, dao.fetchedRanges.size)
    }

    @Test
    fun `onProgress still fires on a cache hit, with the cached count`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeDawarichCacheDao()
        val source = cachingSource(countingClient(requestCount, onePoint), dao)
        source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000))

        val progressUpdates = mutableListOf<FetchProgress>()
        source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000)) { progressUpdates += it }

        assertEquals(listOf(FetchProgress(1, page = 1, totalPages = 1)), progressUpdates)
    }

    @Test
    fun `an empty result well in the past is cached as a real answer, not re-fetched`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeDawarichCacheDao()
        val source = cachingSource(countingClient(requestCount, "[]"), dao)

        source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000))
        val second = source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000))

        assertEquals(1, requestCount.get())
        assertTrue(second.isEmpty())
    }

    // --- recency safeguard: a request whose own end falls inside recentWindow of "now" ---

    @Test
    fun `an empty result inside the recent window is not cached as final, re-fetched next time`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeDawarichCacheDao()
        val fixedNow = Instant.parse("2026-09-09T12:00:00Z")
        // endInclusive is 1 hour before "now" — inside a 24h recentWindow.
        val start = fixedNow.minus(Duration.ofHours(2))
        val end = fixedNow.minus(Duration.ofHours(1))
        val source = cachingSource(countingClient(requestCount, "[]"), dao, recentWindow = Duration.ofHours(24), now = { fixedNow })

        source.fetchTrackPoints(start, end)
        source.fetchTrackPoints(start, end)

        assertEquals(2, requestCount.get())
        assertTrue(dao.fetchedRanges.isEmpty())
    }

    @Test
    fun `an empty result older than the recent window is cached as final`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeDawarichCacheDao()
        val fixedNow = Instant.parse("2026-09-09T12:00:00Z")
        // endInclusive is 25 hours before "now" — outside a 24h recentWindow.
        val start = fixedNow.minus(Duration.ofHours(26))
        val end = fixedNow.minus(Duration.ofHours(25))
        val source = cachingSource(countingClient(requestCount, "[]"), dao, recentWindow = Duration.ofHours(24), now = { fixedNow })

        source.fetchTrackPoints(start, end)
        source.fetchTrackPoints(start, end)

        assertEquals(1, requestCount.get())
        assertEquals(1, dao.fetchedRanges.size)
    }

    @Test
    fun `real points from a recent request are still cached even though completeness is not`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeDawarichCacheDao()
        val fixedNow = Instant.parse("2026-09-09T12:00:00Z")
        val start = fixedNow.minus(Duration.ofHours(2))
        val end = fixedNow.minus(Duration.ofHours(1))
        val source = cachingSource(countingClient(requestCount, onePoint), dao, recentWindow = Duration.ofHours(24), now = { fixedNow })

        source.fetchTrackPoints(start, end)

        assertEquals(1, dao.points.size)
        assertTrue(dao.fetchedRanges.isEmpty())
    }
}
