package com.sultansgame.modmanager.merge

import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Builds the minimal ID catalog from a supplied original config directory. */
class BaseIdCatalogBuilder(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun build(configRoot: File, profileId: String, versionCode: Long, catalogVersion: String): BaseIdCatalog {
        val config = if (configRoot.name == "config") configRoot else File(configRoot, "config")
        require(config.isDirectory) { "原始 config 目录不可读" }
        return BaseIdCatalog(
            profileId = profileId,
            versionCode = versionCode,
            catalogVersion = catalogVersion,
            cards = topLevelKeys(config, "cards.json"),
            tagCodes = topLevelKeys(config, "tag.json"),
            tagIds = rootObjects(config, "tag.json").mapNotNull { it["id"]?.jsonPrimitive?.content?.toIntOrNull() }.toSet(),
            tagNames = rootObjects(config, "tag.json").mapNotNull { it["name"]?.jsonPrimitive?.content }.toSet(),
            over = topLevelKeys(config, "over.json"),
            riteTemplateMappings = topLevelKeys(config, "rite_template_mappings.json"),
            rite = fileIds(config, "rite"),
            event = fileIds(config, "event"),
            loot = fileIds(config, "loot"),
            riteTemplate = fileIds(config, "rite_template"),
        )
    }

    private fun topLevelKeys(config: File, name: String): Set<String> =
        (parse(File(config, name)) as? JsonObject)?.keys.orEmpty()

    private fun rootObjects(config: File, name: String): List<JsonObject> =
        (parse(File(config, name)) as? JsonObject)?.values?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun fileIds(config: File, directory: String): Set<String> =
        File(config, directory).listFiles().orEmpty().filter { it.isFile && it.extension.equals("json", true) }
            .map { it.nameWithoutExtension }.toSet()

    private fun parse(file: File): JsonElement = runCatching {
        json.parseToJsonElement(clean(file.readText(StandardCharsets.UTF_8)))
    }.getOrElse { error("无法解析原始 config：${file.path}: ${it.message}") }

    private fun clean(source: String): String {
        val result = StringBuilder(source.length)
        var quoted = false
        var escaped = false
        var index = 0
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            if (quoted) {
                result.append(current)
                if (escaped) escaped = false else if (current == '\\') escaped = true else if (current == '"') quoted = false
                index++
            } else if (current == '"') {
                quoted = true
                result.append(current)
                index++
            } else if (current == '/' && next == '/') {
                while (index < source.length && source[index] !in "\r\n") index++
            } else if (current == '/' && next == '*') {
                index += 2
                while (index + 1 < source.length && !(source[index] == '*' && source[index + 1] == '/')) index++
                index = (index + 2).coerceAtMost(source.length)
            } else {
                result.append(current)
                index++
            }
        }
        return result.toString().replace(Regex(",\\s*([}\\]])"), "$1")
    }
}
