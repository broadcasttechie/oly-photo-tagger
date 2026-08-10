package com.olyphototagger.app.dawarich

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DawarichClientTest {

    private fun httpClientWith(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun jsonResponse(engine: MockRequestHandleScope, body: String, totalPages: Int = 1) =
        engine.respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(
                "Content-Type" to listOf("application/json"),
                "X-Total-Pages" to listOf(totalPages.toString())
            )
        )

    @Test
    fun `parses the real Dawarich non-slim response shape`() = runTest {
        // Shape captured 2026-08-10 from a live Dawarich 1.10.1 instance (slim=false),
        // trimmed to the fields relevant here — the ~20 others (battery, wifi, geocoding,
        // motion data, etc.) are covered by `ignoreUnknownKeys` and not worth asserting
        // on. First point's altitude uses the legacy integer-column shape (bare number),
        // second uses the newer decimal-column shape (string) — both appear in real data
        // depending on whether a given row has been backfilled.
        val body = """
            [{"id":10320334,"latitude":"52.8901588","longitude":"-2.2047149","timestamp":1786280032,"altitude":120,"velocity":"0","country_name":"","tracker_id":"pixel6pro","battery":null,"geodata":{}},
             {"id":10320333,"latitude":"52.890059","longitude":"-2.204761","timestamp":1786279969,"altitude":"119.5","velocity":"0","country_name":"","tracker_id":"pixel6pro","battery":null,"geodata":{}}]
        """.trimIndent()
        val engine = MockEngine { jsonResponse(this, body) }
        val client = DawarichClient(httpClientWith(engine), "https://dawarich.example", "test-token")

        val points = client.fetchTrackPoints(Instant.EPOCH, Instant.now())

        assertEquals(2, points.size)
        assertEquals(52.890059, points.first().latitude, 1e-9)
        assertEquals(-2.204761, points.first().longitude, 1e-9)
        assertEquals(Instant.ofEpochSecond(1786279969), points.first().time)
        assertEquals(119.5, points.first().altitudeMeters!!, 1e-9)
        assertEquals(120.0, points.last().altitudeMeters!!, 1e-9)
    }

    @Test
    fun `follows pagination until the last page`() = runTest {
        val requestedPages = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val page = request.url.parameters["page"]
            requestedPages += page
            val body = when (page) {
                "1" -> """[{"latitude":"1.0","longitude":"1.0","timestamp":100}]"""
                "2" -> """[{"latitude":"2.0","longitude":"2.0","timestamp":200}]"""
                else -> "[]"
            }
            jsonResponse(this, body, totalPages = 2)
        }
        val client = DawarichClient(httpClientWith(engine), "https://dawarich.example", "token")

        val points = client.fetchTrackPoints(Instant.EPOCH, Instant.now())

        assertEquals(listOf("1", "2"), requestedPages)
        assertEquals(2, points.size)
    }

    @Test
    fun `sorts points ascending regardless of server order`() = runTest {
        val body = """[{"latitude":"2.0","longitude":"2.0","timestamp":200},{"latitude":"1.0","longitude":"1.0","timestamp":100}]"""
        val engine = MockEngine { jsonResponse(this, body) }
        val client = DawarichClient(httpClientWith(engine), "https://dawarich.example", "token")

        val points = client.fetchTrackPoints(Instant.EPOCH, Instant.now())

        assertEquals(
            listOf(Instant.ofEpochSecond(100), Instant.ofEpochSecond(200)),
            points.map { it.time }
        )
    }

    @Test
    fun `points with unparsable coordinates are skipped not thrown`() = runTest {
        val body = """[{"latitude":"not-a-number","longitude":"1.0","timestamp":100},{"latitude":"2.0","longitude":"2.0","timestamp":200}]"""
        val engine = MockEngine { jsonResponse(this, body) }
        val client = DawarichClient(httpClientWith(engine), "https://dawarich.example", "token")

        val points = client.fetchTrackPoints(Instant.EPOCH, Instant.now())

        assertEquals(1, points.size)
        assertEquals(2.0, points.single().latitude, 1e-9)
    }

    @Test
    fun `sends bearer auth header and expected query parameters, no double slash in path`() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            jsonResponse(this, "[]")
        }
        val client = DawarichClient(httpClientWith(engine), "https://dawarich.example/", "secret-token")

        client.fetchTrackPoints(Instant.ofEpochSecond(1000), Instant.ofEpochSecond(2000))

        val req = requireNotNull(captured)
        assertEquals("Bearer secret-token", req.headers[HttpHeaders.Authorization])
        assertEquals("1000", req.url.parameters["start_at"])
        assertEquals("2000", req.url.parameters["end_at"])
        assertEquals("false", req.url.parameters["slim"])
        assertTrue(req.url.encodedPath.endsWith("/api/v1/points"))
        assertTrue(!req.url.encodedPath.contains("//api"))
    }

    @Test(expected = DawarichApiException::class)
    fun `non-success status throws`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val client = DawarichClient(httpClientWith(engine), "https://dawarich.example", "bad-token")

        client.fetchTrackPoints(Instant.EPOCH, Instant.now())
    }
}
