package com.olyphototagger.app.geocode

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Same shape as [com.olyphototagger.app.dawarich.createDawarichHttpClient] — see its own
 *  doc for why HttpTimeout is installed explicitly rather than left as Ktor's own
 *  unbounded default. A single reverse-geocode lookup is one small request, nowhere near
 *  Dawarich's many-page fetches, so the same generous 30s just sits as unused headroom
 *  here rather than doing much real work — but it's the same honest bound against a
 *  genuinely stuck connection either way. */
fun createNominatimHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
}
