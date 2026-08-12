package com.sultansgame.modmanager.merge

import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json

class BaseIdCatalogJsonCodec(
    private val json: Json = Json { prettyPrint = true },
) {
    fun encode(catalog: BaseIdCatalog): String = json.encodeToString(BaseIdCatalog.serializer(), catalog)

    fun decode(text: String): BaseIdCatalog = json.decodeFromString(BaseIdCatalog.serializer(), text)

    fun write(catalog: BaseIdCatalog, file: File) {
        require(file.parentFile.mkdirs() || file.parentFile.isDirectory) { "无法创建 catalog 目录" }
        file.writeText(encode(catalog), StandardCharsets.UTF_8)
    }
}

fun BaseIdCatalog.toSummary(): String = buildString {
    appendLine("catalog=${catalogVersion}")
    appendLine("profile=${profileId}")
    appendLine("versionCode=${versionCode}")
    appendLine("cards=${cards.size}")
    appendLine("tags=${tagCodes.size}")
    appendLine("rites=${rite.size}")
    appendLine("events=${event.size}")
}
