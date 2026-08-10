package com.sultansgame.modmanager.ui

import java.util.Locale

internal enum class DeviceInstallWarning {
    Xiaomi,
    OppoOnePlus,
}

private val xiaomiFamilyBrands = setOf("xiaomi", "redmi", "poco")
private val oppoOnePlusBrands = setOf("oppo", "oneplus")

internal fun deviceInstallWarningFor(manufacturer: String, brand: String): DeviceInstallWarning? {
    val identifiers = sequenceOf(manufacturer, brand)
        .map { it.trim().lowercase(Locale.ROOT) }
        .toList()
    return when {
        identifiers.any { it in xiaomiFamilyBrands } -> DeviceInstallWarning.Xiaomi
        identifiers.any { it in oppoOnePlusBrands } -> DeviceInstallWarning.OppoOnePlus
        else -> null
    }
}
