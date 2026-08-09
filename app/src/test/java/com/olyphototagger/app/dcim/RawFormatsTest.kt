package com.olyphototagger.app.dcim

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFormatsTest {

    @Test
    fun `recognizes writable raw formats across manufacturers, not just ORF`() {
        listOf("ORF", "CR2", "CR3", "NEF", "NRW", "ARW", "RW2", "PEF", "SRW", "RAF", "DNG")
            .forEach { assertTrue("$it should be raw", RawFormats.isRaw(it)) }
    }

    @Test
    fun `writable formats report writable`() {
        listOf("ORF", "CR2", "NEF", "ARW", "DNG").forEach {
            assertTrue("$it should be writable", RawFormats.isWritable(it))
        }
    }

    @Test
    fun `read-only raw formats are recognized as raw but not writable`() {
        listOf("3FR", "KDC", "DCR", "K25", "SRF").forEach {
            assertTrue("$it should be raw", RawFormats.isRaw(it))
            assertFalse("$it should not be writable", RawFormats.isWritable(it))
        }
    }

    @Test
    fun `non-raw extensions are neither raw nor writable`() {
        listOf("JPG", "JPEG", "PNG", "MP4", "LOG", "").forEach {
            assertFalse("$it should not be raw", RawFormats.isRaw(it))
            assertFalse("$it should not be writable", RawFormats.isWritable(it))
        }
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(RawFormats.isRaw("orf"))
        assertTrue(RawFormats.isWritable("cr2"))
    }
}
