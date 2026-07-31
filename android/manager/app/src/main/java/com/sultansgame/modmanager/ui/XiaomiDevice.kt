package com.sultansgame.modmanager.ui

import java.util.Locale

private val xiaomiFamilyBrands = setOf("xiaomi", "redmi", "poco")

internal fun isXiaomiFamilyDevice(manufacturer: String, brand: String): Boolean =
    sequenceOf(manufacturer, brand)
        .map { it.trim().lowercase(Locale.ROOT) }
        .any { it in xiaomiFamilyBrands }
