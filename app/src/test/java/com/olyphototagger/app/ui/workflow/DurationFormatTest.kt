package com.olyphototagger.app.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DurationFormatTest {

    @Test
    fun `formatDuration shows only seconds under a minute`() {
        assertEquals("47s", formatDuration(Duration.ofSeconds(47)))
    }

    @Test
    fun `formatDuration shows minutes and seconds at or over a minute`() {
        assertEquals("1m 30s", formatDuration(Duration.ofSeconds(90)))
    }

    @Test
    fun `formatDuration handles zero`() {
        assertEquals("0s", formatDuration(Duration.ZERO))
    }

    @Test
    fun `estimateRemaining is null before anything has completed - no rate to project from yet`() {
        assertNull(estimateRemaining(completed = 0, total = 10, startedAt = Instant.now()))
    }

    @Test
    fun `estimateRemaining is zero once everything has completed`() {
        val result = estimateRemaining(completed = 10, total = 10, startedAt = Instant.now().minusSeconds(30))
        assertEquals(Duration.ZERO, result)
    }

    @Test
    fun `estimateRemaining projects from the observed rate so far`() {
        // 50 of 150 done in 100s observed -> 2s/item -> 100 remaining -> 200s projected.
        val startedAt = Instant.now().minusSeconds(100)
        val result = estimateRemaining(completed = 50, total = 150, startedAt = startedAt)
        assertEquals(200L, result?.seconds)
    }
}
