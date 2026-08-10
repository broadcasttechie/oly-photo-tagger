package com.olyphototagger.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveGpsSourceResolverTest {

    @Test
    fun `GPX explicitly selected is always usable, regardless of Dawarich config`() {
        assertEquals(GpsSourceType.GPX, ActiveGpsSourceResolver.resolve(GpsSourceType.GPX, hasDawarichConfig = false))
        assertEquals(GpsSourceType.GPX, ActiveGpsSourceResolver.resolve(GpsSourceType.GPX, hasDawarichConfig = true))
    }

    @Test
    fun `Dawarich explicitly selected requires it to actually be configured`() {
        assertEquals(GpsSourceType.DAWARICH, ActiveGpsSourceResolver.resolve(GpsSourceType.DAWARICH, hasDawarichConfig = true))
        assertNull(ActiveGpsSourceResolver.resolve(GpsSourceType.DAWARICH, hasDawarichConfig = false))
    }

    @Test
    fun `nothing selected yet auto-defaults to Dawarich if it's already configured`() {
        // Backward compatibility: every install from before this feature existed.
        assertEquals(GpsSourceType.DAWARICH, ActiveGpsSourceResolver.resolve(null, hasDawarichConfig = true))
    }

    @Test
    fun `nothing selected and nothing configured resolves to nothing usable`() {
        assertNull(ActiveGpsSourceResolver.resolve(null, hasDawarichConfig = false))
    }
}
