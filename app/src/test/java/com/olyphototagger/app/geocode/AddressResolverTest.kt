package com.olyphototagger.app.geocode

import com.olyphototagger.app.cache.AddressCacheDao
import com.olyphototagger.app.cache.AddressCacheEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AddressResolverTest {

    private class FakeAddressCacheDao : AddressCacheDao {
        val entries = mutableMapOf<String, AddressCacheEntity>()
        override suspend fun find(bucketKey: String): AddressCacheEntity? = entries[bucketKey]
        override suspend fun insert(entry: AddressCacheEntity) { entries[entry.bucketKey] = entry }
        override suspend fun clear() = entries.clear()
    }

    private fun jsonResponse(engine: MockRequestHandleScope, body: String) =
        engine.respond(content = body, status = HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("application/json")))

    /** [requestCount] lets a test assert the network was (or wasn't) actually hit — same
     *  reasoning as CachingDawarichSourceTest's countingClient. */
    private fun countingClient(requestCount: AtomicInteger, displayName: String = "Somewhere"): NominatimClient {
        val engine = MockEngine {
            requestCount.incrementAndGet()
            jsonResponse(this, """{"display_name":"$displayName"}""")
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return NominatimClient(httpClient)
    }

    @Test
    fun `a cache hit never touches the network`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeAddressCacheDao()
        dao.entries[bucketKey(51.5074, -0.1278)] = AddressCacheEntity(bucketKey(51.5074, -0.1278), "Cached Address")
        val resolver = AddressResolver(dao, countingClient(requestCount), now = { currentTime })

        val address = resolver.resolve(51.5074, -0.1278)

        assertEquals("Cached Address", address)
        assertEquals(0, requestCount.get())
    }

    @Test
    fun `a cold lookup hits the network once and caches the result`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeAddressCacheDao()
        val resolver = AddressResolver(dao, countingClient(requestCount, "10 Downing Street"), now = { currentTime })

        val address = resolver.resolve(51.5074, -0.1278)

        assertEquals("10 Downing Street", address)
        assertEquals(1, requestCount.get())
        assertEquals("10 Downing Street", dao.entries[bucketKey(51.5074, -0.1278)]?.address)
    }

    @Test
    fun `a repeat lookup for the same bucket is served from cache, no second request`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeAddressCacheDao()
        val resolver = AddressResolver(dao, countingClient(requestCount), now = { currentTime })

        resolver.resolve(51.5074, -0.1278)
        resolver.resolve(51.50745, -0.12784) // same 3dp bucket, not identical coordinates
        val second = resolver.resolve(51.5074, -0.1278)

        assertEquals(1, requestCount.get())
        assertEquals("Somewhere", second)
    }

    @Test
    fun `the very first lookup this resolver ever makes is not delayed`() = runTest {
        val dao = FakeAddressCacheDao()
        val resolver = AddressResolver(dao, countingClient(AtomicInteger(0)), minIntervalMillis = 1_100L, now = { currentTime })

        resolver.resolve(51.5074, -0.1278)

        // No prior request to rate-limit against — should return with no virtual time spent.
        assertEquals(0L, currentTime)
    }

    @Test
    fun `two different uncached buckets are spaced at least minIntervalMillis apart`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeAddressCacheDao()
        val resolver = AddressResolver(dao, countingClient(requestCount), minIntervalMillis = 1_100L, now = { currentTime })

        resolver.resolve(51.5074, -0.1278) // bucket A
        val afterFirst = currentTime
        resolver.resolve(48.8566, 2.3522) // bucket B — genuinely different, real second request
        val afterSecond = currentTime

        assertEquals(2, requestCount.get())
        assertTrue("expected >=1100ms of virtual time between requests, was ${afterSecond - afterFirst}", afterSecond - afterFirst >= 1_100L)
    }

    @Test
    fun `a failed lookup is not cached, so a later attempt retries for real`() = runTest {
        val requestCount = AtomicInteger(0)
        val dao = FakeAddressCacheDao()
        val engine = MockEngine {
            requestCount.incrementAndGet()
            respond("", HttpStatusCode.InternalServerError)
        }
        val client = NominatimClient(HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
        val resolver = AddressResolver(dao, client, now = { currentTime })

        val first = resolver.resolve(51.5074, -0.1278)
        val second = resolver.resolve(51.5074, -0.1278)

        assertNull(first)
        assertNull(second)
        assertEquals(2, requestCount.get()) // retried both times, never cached the failure
        assertTrue(dao.entries.isEmpty())
    }
}
