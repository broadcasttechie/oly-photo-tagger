package com.olyphototagger.app.dcim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

class FileIdentityTest {

    private fun file(
        name: String = "P8090001.JPG",
        folder: String = "100OLYMP",
        size: Long = 1024,
        modified: Instant? = Instant.parse("2026-08-09T14:00:00Z")
    ) = CameraFile(
        uriString = "content://camera/$folder/$name",
        displayName = name,
        folderName = folder,
        sizeBytes = size,
        lastModified = modified
    )

    @Test
    fun `identical metadata produces the same key`() {
        assertEquals(file().identityKey(), file().identityKey())
    }

    @Test
    fun `different size changes the key even with the same name and folder`() {
        // Guards against a reused filename after a card format masking a different photo.
        assertNotEquals(file(size = 1024).identityKey(), file(size = 2048).identityKey())
    }

    @Test
    fun `different last-modified changes the key`() {
        assertNotEquals(
            file(modified = Instant.parse("2026-08-09T14:00:00Z")).identityKey(),
            file(modified = Instant.parse("2026-08-09T15:00:00Z")).identityKey()
        )
    }

    @Test
    fun `different folder changes the key even with identical name size and mtime`() {
        assertNotEquals(
            file(folder = "100OLYMP").identityKey(),
            file(folder = "101OLYMP").identityKey()
        )
    }

    @Test
    fun `different display name changes the key`() {
        assertNotEquals(
            file(name = "P8090001.JPG").identityKey(),
            file(name = "P8090002.JPG").identityKey()
        )
    }

    @Test
    fun `null last-modified does not throw and is distinct from any real timestamp`() {
        val withNull = file(modified = null).identityKey()
        val withReal = file(modified = Instant.EPOCH).identityKey()
        assertNotEquals(withNull, withReal)
    }
}
