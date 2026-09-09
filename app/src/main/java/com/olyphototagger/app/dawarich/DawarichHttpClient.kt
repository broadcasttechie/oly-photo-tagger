package com.olyphototagger.app.dawarich

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * ignoreUnknownKeys guards against Dawarich adding response fields in future versions.
 *
 * HttpTimeout is not the default without installing it — confirmed for real (2026-09-09)
 * that a request with no timeout configured can hang past the point Android itself gives
 * up and raises an ANR on whatever triggered it, regardless of how that caller is
 * structured. 30s is generous against DawarichClient's own real per-page timing (~1.1-1.4s
 * normally) while still catching a genuinely stuck connection — this bounds *one page*,
 * not a whole (potentially many-page) [com.olyphototagger.app.geotag.GpsSource.fetchTrackPoints]
 * call, which by design can legitimately take much longer than that for a wide date range.
 */
fun createDawarichHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
}
