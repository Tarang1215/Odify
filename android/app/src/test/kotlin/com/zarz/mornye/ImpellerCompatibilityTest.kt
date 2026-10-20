package com.zarz.mornye

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpellerCompatibilityTest {
    @Test
    fun knownRasterCrashUsesFallbackOnTheReportedDeviceAndAndroidVersion() {
        assertTrue(hasKnownImpellerRasterCrash(35, "Xiaomi", "25053PC47I"))
        assertTrue(hasKnownImpellerRasterCrash(35, "XIAOMI", "25053pc47i"))
    }

    @Test
    fun otherDevicesAndAndroidVersionsKeepTheirExistingRendererPolicy() {
        assertFalse(hasKnownImpellerRasterCrash(34, "Xiaomi", "25053PC47I"))
        assertFalse(hasKnownImpellerRasterCrash(36, "Xiaomi", "25053PC47I"))
        assertFalse(hasKnownImpellerRasterCrash(37, "Xiaomi", "25053PC47I"))
        assertFalse(hasKnownImpellerRasterCrash(35, "Xiaomi", "25053PC47G"))
        assertFalse(hasKnownImpellerRasterCrash(35, "Other", "25053PC47I"))
    }
}
