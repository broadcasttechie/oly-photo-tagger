package com.olyphototagger.app.write

import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StrayArtifactIndexTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun index() = StrayArtifactIndex(contentResolver = null)
    private fun parent() = DocumentFile.fromFile(tempFolder.root)

    @Test
    fun `finds a stray temp and recovers its original name`() = runTest {
        tempFolder.newFile("P8080743.JPG")
        tempFolder.newFile("P8080743.JPG.tmp")

        val temps = index().strayTemps(parent(), "P8080743.JPG")

        assertEquals(1, temps.size)
        assertEquals("P8080743.JPG.tmp", temps.single().name)
    }

    @Test
    fun `finds a stray backup and recovers its original name`() = runTest {
        tempFolder.newFile("P8080743.JPG")
        tempFolder.newFile("P8080743.JPG.bak")

        assertTrue(index().hasStrayBackup(parent(), "P8080743.JPG"))
    }

    @Test
    fun `reports no artifacts for a plain untagged file`() = runTest {
        tempFolder.newFile("P8080743.JPG")

        val idx = index()
        assertTrue(idx.strayTemps(parent(), "P8080743.JPG").isEmpty())
        assertFalse(idx.hasStrayBackup(parent(), "P8080743.JPG"))
    }

    @Test
    fun `keeps different files' artifacts separate`() = runTest {
        tempFolder.newFile("P8080743.JPG.tmp")
        tempFolder.newFile("P8080744.JPG.bak")

        val idx = index()
        assertEquals(1, idx.strayTemps(parent(), "P8080743.JPG").size)
        assertTrue(idx.strayTemps(parent(), "P8080744.JPG").isEmpty())
        assertFalse(idx.hasStrayBackup(parent(), "P8080743.JPG"))
        assertTrue(idx.hasStrayBackup(parent(), "P8080744.JPG"))
    }

    @Test
    fun `original name lookup is case-insensitive, matching the old per-call scan`() = runTest {
        tempFolder.newFile("P8080743.JPG.tmp")

        assertEquals(1, index().strayTemps(parent(), "p8080743.jpg").size)
    }

    @Test
    fun `a lookup for a name with no matching artifact returns empty rather than throwing`() = runTest {
        val idx = index()

        assertTrue(idx.strayTemps(parent(), "NeverExisted.JPG").isEmpty())
        assertFalse(idx.hasStrayBackup(parent(), "NeverExisted.JPG"))
    }

    @Test
    fun `only queries the folder once across repeated lookups against the same parent`() = runTest {
        tempFolder.newFile("P8080743.JPG.tmp")
        val idx = index()
        val parent = parent()

        idx.strayTemps(parent, "P8080743.JPG")
        // A second real file appearing after the index was already built for this parent
        // must NOT show up — proves the scan genuinely only happened once, not once per
        // lookup, which is the entire point of this class.
        tempFolder.newFile("P8080744.JPG.tmp")

        assertTrue(idx.strayTemps(parent, "P8080744.JPG").isEmpty())
    }

    @Test
    fun `a fresh index queries again and sees artifacts created since`() = runTest {
        tempFolder.newFile("P8080743.JPG.tmp")
        index().strayTemps(parent(), "P8080743.JPG")

        tempFolder.newFile("P8080744.JPG.tmp")

        assertEquals(1, index().strayTemps(parent(), "P8080744.JPG").size)
    }
}
