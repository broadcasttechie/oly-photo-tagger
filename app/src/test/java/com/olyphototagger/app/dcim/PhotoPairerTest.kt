package com.olyphototagger.app.dcim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPairerTest {

    private fun file(name: String, folder: String = "100OLYMP") = CameraFile(
        uriString = "content://camera/$folder/$name",
        displayName = name,
        folderName = folder,
        sizeBytes = 1024,
        lastModified = null
    )

    @Test
    fun `matching JPEG and RAW form a complete pair`() {
        val result = PhotoPairer.pair(listOf(file("P8090001.JPG"), file("P8090001.ORF")))

        assertEquals(1, result.pairs.size)
        val pair = result.pairs.single()
        assertTrue(pair.isComplete)
        assertEquals("P8090001", pair.baseName)
        assertEquals("100OLYMP", pair.folderName)
        assertTrue(result.ignored.isEmpty())
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `JPEG with no RAW is an incomplete orphan pair`() {
        val result = PhotoPairer.pair(listOf(file("P8090001.JPG")))

        val pair = result.pairs.single()
        assertEquals(false, pair.isComplete)
        assertEquals("P8090001.JPG", pair.jpeg?.displayName)
        assertEquals(null, pair.raw)
    }

    @Test
    fun `RAW with no JPEG is an incomplete orphan pair`() {
        val result = PhotoPairer.pair(listOf(file("P8090002.ORF")))

        val pair = result.pairs.single()
        assertEquals(false, pair.isComplete)
        assertEquals(null, pair.jpeg)
        assertEquals("P8090002.ORF", pair.raw?.displayName)
    }

    @Test
    fun `non-photo files are ignored not paired`() {
        val result = PhotoPairer.pair(
            listOf(file("P8090001.JPG"), file("P8090001.ORF"), file("MOVI0001.MP4"))
        )

        assertEquals(1, result.pairs.size)
        assertEquals(1, result.ignored.size)
        assertEquals("MOVI0001.MP4", result.ignored.single().displayName)
    }

    @Test
    fun `gpslog files are ignored`() {
        val result = PhotoPairer.pair(listOf(file("26080101.LOG", folder = "GPSLOG")))

        assertTrue(result.pairs.isEmpty())
        assertEquals(1, result.ignored.size)
    }

    @Test
    fun `same base name in different folders is not merged`() {
        val result = PhotoPairer.pair(
            listOf(
                file("P8090001.JPG", folder = "100OLYMP"),
                file("P8090001.ORF", folder = "100OLYMP"),
                file("P8090001.JPG", folder = "101OLYMP"),
                file("P8090001.ORF", folder = "101OLYMP")
            )
        )

        assertEquals(2, result.pairs.size)
        assertEquals(setOf("100OLYMP", "101OLYMP"), result.pairs.map { it.folderName }.toSet())
        result.pairs.forEach { assertTrue(it.isComplete) }
    }

    @Test
    fun `extension and base name casing does not affect pairing`() {
        val result = PhotoPairer.pair(listOf(file("p8090001.jpg"), file("P8090001.orf")))

        assertEquals(1, result.pairs.size)
        assertTrue(result.pairs.single().isComplete)
    }

    @Test
    fun `duplicate JPEGs for one base name are flagged as conflicts not guessed`() {
        val a = file("P8090001.JPG")
        val b = file("P8090001.JPG").copy(uriString = "content://camera/100OLYMP/dup.JPG")
        val raw = file("P8090001.ORF")

        val result = PhotoPairer.pair(listOf(a, b, raw))

        assertTrue(result.pairs.isEmpty())
        assertEquals(3, result.conflicts.size)
    }

    @Test
    fun `realistic folder listing mixes complete pairs, orphans, ignored, and conflicts`() {
        val files = listOf(
            file("P8090001.JPG"), file("P8090001.ORF"), // complete pair
            file("P8090002.JPG"),                        // JPEG-only orphan
            file("P8090003.ORF"),                        // RAW-only orphan
            file("P8090004.JPG"), file("P8090004.JPG").copy(uriString = "dup"), // conflict
            file("VIDEO001.MP4")                          // ignored
        )

        val result = PhotoPairer.pair(files)

        assertEquals(3, result.pairs.size)
        assertEquals(1, result.pairs.count { it.isComplete })
        assertEquals(2, result.pairs.count { !it.isComplete })
        assertEquals(2, result.conflicts.size)
        assertEquals(1, result.ignored.size)
    }
}
