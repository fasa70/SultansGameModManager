package com.sultansgame.modmanager.merge

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * No-base-JSON Mod overlay merger.
 *
 * ID conflict detection and remapping are owned by the upstream Python worker.
 * This class only consumes remapped roots and overlays Mod content without
 * interpreting absent fields as deletions from the unavailable game base.
 */
class ModMergeEngine(
    private val json: Json = Json { prettyPrint = true; prettyPrintIndent = "    " },
    private val repairJson: ((String) -> String)? = null,
) {
    fun mergeRemapped(
        orderedRoots: List<File>,
        outputRoot: File,
        modNames: List<String> = orderedRoots.map(File::getName),
    ): MergePreflight {
        require(orderedRoots.size >= 2) { "至少选择两个 Mod" }
        require(orderedRoots.size == modNames.size) { "Mod 名称数量不匹配" }
        if (outputRoot.exists()) outputRoot.deleteRecursively()
        require(outputRoot.mkdirs() || outputRoot.isDirectory) { "无法创建合并输出目录" }

        val merged = linkedMapOf<String, JsonElement>()
        val resources = linkedMapOf<String, File>()
        orderedRoots.forEach { root ->
            require(root.isDirectory) { "重映射后的 Mod 工作目录不可读：${root.name}" }
            root.walkTopDown().filter(File::isFile).forEach { source ->
                val relative = source.relativeTo(root).invariantSeparatorsPath
                if (relative.equals("Info.json", ignoreCase = true)) return@forEach
                if (relative.endsWith(".json", ignoreCase = true)) {
                    val incoming = parse(source.readText(StandardCharsets.UTF_8), relative)
                    merged[relative] = mergeOverlay(merged[relative], incoming, relative)
                } else {
                    resources[relative] = source
                }
            }
        }

        merged.forEach { (relative, document) -> writeJson(outputRoot, relative, document) }
        resources.forEach { (relative, source) ->
            val destination = outputRoot.resolve(relative)
            require(destination.parentFile.mkdirs() || destination.parentFile.isDirectory) {
                "无法创建资源目录"
            }
            source.copyTo(destination, overwrite = true)
        }
        writeInfo(outputRoot, modNames)
        return MergePreflight(emptyList(), 0, null)
    }

    /** Compatibility entry point for callers which already performed remapping. */
    @Deprecated("Run the upstream Python remapper first and call mergeRemapped")
    fun merge(
        orderedRoots: List<File>,
        @Suppress("UNUSED_PARAMETER") catalogSelection: CatalogSelection,
        outputRoot: File,
        modNames: List<String> = orderedRoots.map(File::getName),
    ): MergePreflight = mergeRemapped(orderedRoots, outputRoot, modNames)

    private fun parse(text: String, relative: String): JsonElement = runCatching {
        json.parseToJsonElement(repairJson?.invoke(text) ?: cleanJson(text))
    }.getOrElse {
        val detail = it.message ?: "格式无效"
        error("JSON 解析失败（$relative）：$detail")
    }

    private fun cleanJson(source: String): String {
        val withoutComments = StringBuilder(source.length)
        var quoted = false
        var escaped = false
        var index = 0
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when {
                quoted -> {
                    if (escaped) {
                        withoutComments.append(current)
                        escaped = false
                    } else {
                        when (current) {
                            '\\' -> {
                                withoutComments.append(current)
                                escaped = true
                            }
                            '"' -> {
                                withoutComments.append(current)
                                quoted = false
                            }
                            '\b' -> withoutComments.append('\\').append('b')
                            12.toChar() -> withoutComments.append('\\').append('f')
                            '\n' -> withoutComments.append('\\').append('n')
                            '\r' -> withoutComments.append('\\').append('r')
                            '\t' -> withoutComments.append('\\').append('t')
                            else -> if (current.code in 0..0x1f) {
                                withoutComments.append('\\').append("u%04x".format(current.code))
                            } else {
                                withoutComments.append(current)
                            }
                        }
                    }
                    index++
                }
                current == '"' -> {
                    quoted = true
                    withoutComments.append(current)
                    index++
                }
                current == '/' && next == '/' -> {
                    while (index < source.length && source[index] !in "\r\n") index++
                }
                current == '/' && next == '*' -> {
                    index += 2
                    while (index + 1 < source.length &&
                        !(source[index] == '*' && source[index + 1] == '/')) {
                        index++
                    }
                    index = (index + 2).coerceAtMost(source.length)
                }
                else -> {
                    withoutComments.append(current)
                    index++
                }
            }
        }

        val result = StringBuilder(withoutComments.length)
        quoted = false
        escaped = false
        index = 0
        while (index < withoutComments.length) {
            val current = withoutComments[index]
            when {
                quoted -> {
                    result.append(current)
                    if (escaped) escaped = false
                    else if (current == '\\') escaped = true
                    else if (current == '"') quoted = false
                    index++
                }
                current == '"' -> {
                    quoted = true
                    result.append(current)
                    index++
                }
                current == ',' -> {
                    var next = index + 1
                    while (next < withoutComments.length && withoutComments[next].isWhitespace()) next++
                    if (next >= withoutComments.length || withoutComments[next] !in "]}") result.append(current)
                    index++
                }
                else -> {
                    result.append(current)
                    index++
                }
            }
        }
        return result.toString()
    }

    private fun mergeOverlay(existing: JsonElement?, incoming: JsonElement, relative: String): JsonElement = when {
        existing == null -> incoming
        relative.substringAfterLast('/') == "sfx_config.json" -> incoming
        else -> deepMerge(existing, incoming, "")
    }

    private fun deepMerge(base: JsonElement, override: JsonElement, key: String): JsonElement = when {
        base is JsonObject && override is JsonObject -> buildJsonObject {
            base.forEach { (name, value) -> put(name, value) }
            override.forEach { (name, value) ->
                put(name, base[name]?.let { deepMerge(it, value, name) } ?: value)
            }
        }
        base is JsonArray && override is JsonArray && key in SMART_ARRAY_FIELDS ->
            mergeSmartArray(base, override)
        else -> override
    }

    private fun mergeSmartArray(base: JsonArray, override: JsonArray): JsonArray = buildJsonArray {
        val result = base.toMutableList()
        override.forEach { incoming ->
            val identity = arrayIdentity(incoming)
            val match = identity?.let { wanted -> result.indexOfFirst { arrayIdentity(it) == wanted } } ?: -1
            if (match >= 0) result[match] = deepMerge(result[match], incoming, "")
            else result += incoming
        }
        result.forEach(::add)
    }

    private fun arrayIdentity(element: JsonElement): String? {
        val value = element as? JsonObject ?: return null
        value["guid"]?.jsonPrimitive?.content?.let { return "guid:$it" }
        value["id"]?.jsonPrimitive?.content?.let { return "id:$it" }
        value["tag"]?.jsonPrimitive?.content?.let { return "tag:$it" }
        value["key"]?.jsonPrimitive?.content?.let { return "key:$it" }
        value["action"]?.let { return "action:$it" }
        value["condition"]?.let { return "condition:$it" }
        val title = value["result_title"]?.jsonPrimitive?.content
        val text = value["result_text"]?.jsonPrimitive?.content
        return if (title != null || text != null) "result:${title.orEmpty()}|${text.orEmpty()}" else null
    }

    private fun writeJson(root: File, relative: String, value: JsonElement) {
        val target = root.resolve(relative)
        require(target.parentFile.mkdirs() || target.parentFile.isDirectory) { "无法创建 JSON 目录" }
        target.writeText(json.encodeToString(JsonElement.serializer(), value), StandardCharsets.UTF_8)
    }

    private fun writeInfo(root: File, names: List<String>) {
        val content = buildJsonObject {
            put("name", JsonPrimitive("合并Mod - 自动生成"))
            put("description", JsonPrimitive("由 Mod 合并管理器自动生成（无本体 JSON 模式）。\n包含以下 mod 的合并内容：\n" + names.joinToString("\n") { "  - $it" }))
            put("tags", buildJsonArray { add(JsonPrimitive("Merged")) })
            put("version", JsonPrimitive(digest(names).take(16)))
            put("synthetic", JsonPrimitive(true))
            put("merge_mode", JsonPrimitive("no-base-json-overlay"))
        }
        root.resolve("Info.json").writeText(json.encodeToString(JsonObject.serializer(), content), StandardCharsets.UTF_8)
    }

    private fun digest(values: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val SMART_ARRAY_FIELDS = setOf("settlement", "settlement_prior", "settlement_extre")
    }
}
