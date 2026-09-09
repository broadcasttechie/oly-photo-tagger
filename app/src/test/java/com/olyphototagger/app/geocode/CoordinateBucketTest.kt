package com.olyphototagger.app.geocode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoordinateBucketTest {

    @Test
    fun `nearby coordinates within the same bucket produce the same key`() {
        // ~20m apart at this latitude — well inside one 3dp bucket (~111m).
        val a = bucketKey(51.5074, -0.1278)
        val b = bucketKey(51.50745, -0.12784)
        assertEquals(a, b)
    }

    @Test
    fun `coordinates a couple of buckets apart produce different keys`() {
        val a = bucketKey(51.5074, -0.1278)
        val b = bucketKey(51.5150, -0.1400)
        assertNotEquals(a, b)
    }

    @Test
    fun `identical coordinates always produce the same key`() {
        assertEquals(bucketKey(0.0, 0.0), bucketKey(0.0, 0.0))
    }

    @Test
    fun `negative coordinates are handled the same way as positive ones`() {
        val a = bucketKey(-33.8688, 151.2093)
        val b = bucketKey(-33.86885, 151.20932)
        assertEquals(a, b)
    }

    @Test
    fun `precision controls how coarse the bucket is`() {
        val fine = bucketKey(51.50741, -0.12781, precision = 5)
        val coarse = bucketKey(51.50749, -0.12789, precision = 1)
        // Fine precision distinguishes these two nearby points; coarse precision merges them.
        assertNotEquals(fine, bucketKey(51.50749, -0.12789, precision = 5))
        assertEquals(coarse, bucketKey(51.50741, -0.12781, precision = 1))
    }
}
