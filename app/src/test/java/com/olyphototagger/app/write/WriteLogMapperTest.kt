package com.olyphototagger.app.write

import com.olyphototagger.app.cache.WriteLogResultType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class WriteLogMapperTest {

    private val loggedAt = Instant.parse("2026-08-11T12:00:00Z")

    @Test
    fun `Written with no previous tag maps coordinates and leaves detail null`() {
        val result = GpsExifWriteResult.Written(
            previousLatLong = null,
            newLatitude = 53.4808,
            newLongitude = -2.2426,
            newAltitudeMeters = 38.0,
            writtenAt = loggedAt
        )

        val entity = WriteLogMapper.from("100OLYMP", "P8080743.JPG", result, loggedAt)

        assertEquals(WriteLogResultType.WRITTEN.name, entity.resultType)
        assertEquals("100OLYMP", entity.folderName)
        assertEquals("P8080743.JPG", entity.displayName)
        assertEquals(loggedAt.toEpochMilli(), entity.loggedAtEpochMillis)
        assertNull(entity.previousLatitude)
        assertNull(entity.previousLongitude)
        assertEquals(53.4808, entity.newLatitude!!, 0.0)
        assertEquals(-2.2426, entity.newLongitude!!, 0.0)
        assertEquals(38.0, entity.newAltitudeMeters!!, 0.0)
        assertNull(entity.detail)
    }

    @Test
    fun `Written overwriting an existing tag records the previous coordinates too`() {
        val result = GpsExifWriteResult.Written(
            previousLatLong = 51.0 to -1.0,
            newLatitude = 53.4808,
            newLongitude = -2.2426,
            newAltitudeMeters = null,
            writtenAt = loggedAt
        )

        val entity = WriteLogMapper.from("100OLYMP", "P8080743.JPG", result, loggedAt)

        assertEquals(51.0, entity.previousLatitude!!, 0.0)
        assertEquals(-1.0, entity.previousLongitude!!, 0.0)
        assertNull(entity.newAltitudeMeters)
    }

    @Test
    fun `Written with a stray backup notes it in detail without failing the entry`() {
        val result = GpsExifWriteResult.Written(
            previousLatLong = null,
            newLatitude = 53.4808,
            newLongitude = -2.2426,
            newAltitudeMeters = null,
            writtenAt = loggedAt,
            strayBackupFileName = "P8080743.JPG.bak"
        )

        val entity = WriteLogMapper.from("100OLYMP", "P8080743.JPG", result, loggedAt)

        assertEquals(WriteLogResultType.WRITTEN.name, entity.resultType)
        assertEquals("Backup not yet cleaned up: P8080743.JPG.bak", entity.detail)
    }

    @Test
    fun `SkippedAlreadyTagged records the existing coordinates as previous, no new ones`() {
        val result = GpsExifWriteResult.SkippedAlreadyTagged(existingLatitude = 51.0, existingLongitude = -1.0)

        val entity = WriteLogMapper.from("100OLYMP", "P8080743.JPG", result, loggedAt)

        assertEquals(WriteLogResultType.SKIPPED_ALREADY_TAGGED.name, entity.resultType)
        assertEquals(51.0, entity.previousLatitude!!, 0.0)
        assertEquals(-1.0, entity.previousLongitude!!, 0.0)
        assertNull(entity.newLatitude)
        assertNull(entity.newLongitude)
    }

    @Test
    fun `UnsupportedFormat records the mime type in detail`() {
        val result = GpsExifWriteResult.UnsupportedFormat(mimeType = "image/x-canon-cr2")

        val entity = WriteLogMapper.from("100OLYMP", "P8080743.CR2", result, loggedAt)

        assertEquals(WriteLogResultType.UNSUPPORTED_FORMAT.name, entity.resultType)
        assertEquals("Unsupported format (image/x-canon-cr2)", entity.detail)
    }

    @Test
    fun `UnsupportedFormat with a null mime type still produces a readable detail`() {
        val result = GpsExifWriteResult.UnsupportedFormat(mimeType = null)

        val entity = WriteLogMapper.from("100OLYMP", "P8080743.XYZ", result, loggedAt)

        assertEquals("Unsupported format", entity.detail)
    }

    @Test
    fun `Failed records the reason in detail`() {
        val result = GpsExifWriteResult.Failed("Verification mismatch after write")

        val entity = WriteLogMapper.from("100OLYMP", "P8080743.JPG", result, loggedAt)

        assertEquals(WriteLogResultType.FAILED.name, entity.resultType)
        assertEquals("Verification mismatch after write", entity.detail)
    }

    @Test
    fun `BackupArtifactPresent records the backup file name in detail`() {
        val result = GpsExifWriteResult.BackupArtifactPresent("P8080743.JPG.bak")

        val entity = WriteLogMapper.from("100OLYMP", "P8080743.JPG", result, loggedAt)

        assertEquals(WriteLogResultType.BACKUP_ARTIFACT_PRESENT.name, entity.resultType)
        assertEquals("Backup present from an earlier interrupted write: P8080743.JPG.bak", entity.detail)
    }

    @Test
    fun `NeedsRecovery records both temp and backup file names in detail`() {
        val result = GpsExifWriteResult.NeedsRecovery(tempFileName = "P8080743.JPG.tmp", backupFileName = "P8080743.JPG.bak")

        val entity = WriteLogMapper.from("100OLYMP", "P8080743.JPG", result, loggedAt)

        assertEquals(WriteLogResultType.NEEDS_RECOVERY.name, entity.resultType)
        assertEquals("Interrupted mid-write — temp: P8080743.JPG.tmp, backup: P8080743.JPG.bak", entity.detail)
    }
}
