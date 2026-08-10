package com.sultansgame.modmanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceInstallWarningTest {
    @Test
    fun recognizesXiaomiFamilyManufacturerOrBrand() {
        assertEquals(DeviceInstallWarning.Xiaomi, deviceInstallWarningFor("Xiaomi", "Xiaomi"))
        assertEquals(DeviceInstallWarning.Xiaomi, deviceInstallWarningFor("unknown", "Redmi"))
        assertEquals(DeviceInstallWarning.Xiaomi, deviceInstallWarningFor("unknown", "POCO"))
    }

    @Test
    fun recognizesOppoAndOnePlusManufacturerOrBrand() {
        assertEquals(DeviceInstallWarning.OppoOnePlus, deviceInstallWarningFor("OPPO", "unknown"))
        assertEquals(DeviceInstallWarning.OppoOnePlus, deviceInstallWarningFor("unknown", "OnePlus"))
    }

    @Test
    fun matchingIsTrimmedAndCaseInsensitive() {
        assertEquals(DeviceInstallWarning.Xiaomi, deviceInstallWarningFor("  XIAOMI  ", "unknown"))
        assertEquals(DeviceInstallWarning.OppoOnePlus, deviceInstallWarningFor("  oPpO  ", "unknown"))
        assertEquals(DeviceInstallWarning.OppoOnePlus, deviceInstallWarningFor("unknown", " onePLUS "))
    }

    @Test
    fun xiaomiFamilyTakesPriority() {
        assertEquals(DeviceInstallWarning.Xiaomi, deviceInstallWarningFor("Xiaomi", "OPPO"))
    }

    @Test
    fun requiresAnExactBrandMatch() {
        assertNull(deviceInstallWarningFor("Xiaomi Communications Co Ltd", "POCOX"))
        assertNull(deviceInstallWarningFor("OPPO Reno", "OnePlus Technology"))
        assertNull(deviceInstallWarningFor("Samsung", "Samsung"))
    }
}
