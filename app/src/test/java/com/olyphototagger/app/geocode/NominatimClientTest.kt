package com.olyphototagger.app.geocode

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NominatimClientTest {

    private fun httpClientWith(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun jsonResponse(engine: MockRequestHandleScope, body: String) =
        engine.respond(content = body, status = HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("application/json")))

    @Test
    fun `parses display_name from a successful response`() = runTest {
        val body = """{"place_id":123,"lat":"51.5074","lon":"-0.1278","display_name":"10 Downing Street, Westminster, London, United Kingdom"}"""
        val engine = MockEngine { jsonResponse(this, body) }
        val client = NominatimClient(httpClientWith(engine))

        val address = client.reverseGeocode(51.5074, -0.1278)

        assertEquals("10 Downing Street, Westminster, London, United Kingdom", address)
    }

    @Test
    fun `a response with no display_name returns null`() = runTest {
        val engine = MockEngine { jsonResponse(this, """{"error":"Unable to geocode"}""") }
        val client = NominatimClient(httpClientWith(engine))

        assertNull(client.reverseGeocode(0.0, 0.0))
    }

    @Test(expected = NominatimApiException::class)
    fun `non-success status throws`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.TooManyRequests) }
        val client = NominatimClient(httpClientWith(engine))

        client.reverseGeocode(51.5074, -0.1278)
    }

    @Test
    fun `sends the required identifying User-Agent and expected query parameters`() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            jsonResponse(this, """{"display_name":"Somewhere"}""")
        }
        val client = NominatimClient(httpClientWith(engine))

        client.reverseGeocode(51.5074, -0.1278)

        val req = requireNotNull(captured)
        assertTrue(req.headers[HttpHeaders.UserAgent]!!.startsWith("OlyPhotoTagger/"))
        assertEquals("jsonv2", req.url.parameters["format"])
        assertEquals("51.5074", req.url.parameters["lat"])
        assertEquals("-0.1278", req.url.parameters["lon"])
    }
}
