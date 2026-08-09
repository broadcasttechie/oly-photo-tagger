package com.olyphototagger.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class BaseUrlNormalizerTest {

    @Test
    fun `bare host gets https prefix`() {
        assertEquals(
            "https://dawarich.example.com",
            BaseUrlNormalizer.normalize("dawarich.example.com")
        )
    }

    @Test
    fun `trailing slash is trimmed`() {
        assertEquals(
            "https://dawarich.example.com",
            BaseUrlNormalizer.normalize("https://dawarich.example.com/")
        )
    }

    @Test
    fun `existing http scheme is preserved for local unencrypted instances`() {
        assertEquals(
            "http://192.168.1.50:3000",
            BaseUrlNormalizer.normalize("http://192.168.1.50:3000")
        )
    }

    @Test
    fun `scheme check is case insensitive and not double-prefixed`() {
        assertEquals(
            "HTTPS://Foo.example.com",
            BaseUrlNormalizer.normalize("HTTPS://Foo.example.com")
        )
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            "https://dawarich.example.com",
            BaseUrlNormalizer.normalize("  dawarich.example.com  ")
        )
    }
}
