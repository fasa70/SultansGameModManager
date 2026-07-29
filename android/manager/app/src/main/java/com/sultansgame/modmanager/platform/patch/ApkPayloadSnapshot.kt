package com.sultansgame.modmanager.platform.patch

import java.io.File
import java.util.zip.ZipFile

internal data class ApkPayloadEntry(
    val name: String,
    val size: Long,
    val crc: Long,
    val method: Int,
)

internal fun File.payloadSnapshot(): List<ApkPayloadEntry> = ZipFile(this).use { archive ->
    archive.entries().asSequence()
        .filterNot { it.isDirectory || it.name.startsWith("META-INF/") }
        .map { entry ->
            ApkPayloadEntry(
                name = entry.name,
                size = entry.size,
                crc = entry.crc,
                method = entry.method,
            )
        }
        .sortedBy(ApkPayloadEntry::name)
        .toList()
}
