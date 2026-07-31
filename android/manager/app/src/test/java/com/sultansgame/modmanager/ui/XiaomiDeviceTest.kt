package com.sultansgame.modmanager.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiDeviceTest {
    @Test
    fun recognizesXiaomiFamilyManufacturerOrBrand() {
        assertTrue(isXiaomiFamilyDevice("Xiaomi", "Xiaomi"))
        assertTrue(isXiaomiFamilyDevice("unknown", "Redmi"))
        assertTrue(isXiaomiFamilyDevice("unknown", "POCO"))
    }

    @Test
    fun matchingIsTrimmedAndCaseInsensitive() {
        assertTrue(isXiaomiFamilyDevice("  XIAOMI  ", "unknown"))
    }

    @Test
    fun requiresAnExactBrandMatch() {
        assertFalse(isXiaomiFamilyDevice("Xiaomi Communications Co Ltd", "POCOX"))
        assertFalse(isXiaomiFamilyDevice("Samsung", "Samsung"))
    }
}
