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

class ModMergeEngine(
    private val json: Json = Json { prettyPrint = true; prettyPrintIndent = "    " },
) {
    fun preflight(orderedRoots: List<File>, catalogSelection: CatalogSelection): MergePreflight {
        require(orderedRoots.size >= 2) { "至少选择两个 Mod" }
        val definitions = collectDefinitions(orderedRoots)
        val conflicts = findConflicts(definitions, catalogSelection.catalog)
        val remaps = allocateRemaps(conflicts, definitions, orderedRoots.size)
        return MergePreflight(conflicts, remaps.values.sumOf { it.size }, catalogSelection.warning)
    }

    fun merge(
        orderedRoots: List<File>,
        catalogSelection: CatalogSelection,
        outputRoot: File,
        modNames: List<String> = orderedRoots.map(File::getName),
    ): MergePreflight {
        require(orderedRoots.size >= 2) { "至少选择两个 Mod" }
        require(orderedRoots.size == modNames.size) { "Mod 名称数量不匹配" }
        require(outputRoot.mkdirs() || outputRoot.isDirectory) { "无法创建合并输出目录" }
        val definitions = collectDefinitions(orderedRoots)
        val conflicts = findConflicts(definitions, catalogSelection.catalog)
        val remaps = allocateRemaps(conflicts, definitions, orderedRoots.size)
        val merged = linkedMapOf<String, JsonElement>()
        val resources = linkedMapOf<String, File>()

        orderedRoots.forEachIndexed { modIndex, root ->
            val remap = remaps[modIndex] ?: EMPTY_REMAP
            root.walkTopDown().filter(File::isFile).forEach { source ->
                val relative = source.relativeTo(root).invariantSeparatorsPath
                if (relative.equals("Info.json", ignoreCase = true)) return@forEach
                val mappedRelative = remapPath(relative, remap)
                if (relative.endsWith(".json", ignoreCase = true)) {
                    val mapped = remapElement(parse(source.readText(StandardCharsets.UTF_8)), remap)
                    merged[mappedRelative] = mergeFile(merged[mappedRelative], mapped, mappedRelative)
                } else {
                    resources[mappedRelative] = source
                }
            }
        }
        merged.forEach { (relative, document) -> writeJson(outputRoot, relative, document) }
        resources.forEach { (relative, source) ->
            val destination = outputRoot.resolve(relative)
            require(destination.parentFile.mkdirs() || destination.parentFile.isDirectory) { "无法创建资源目录" }
            source.copyTo(destination, overwrite = true)
        }
        writeInfo(outputRoot, modNames)
        return MergePreflight(conflicts, remaps.values.sumOf { it.size }, catalogSelection.warning)
    }

    private fun findConflicts(definitions: Map<EntityKey, List<Int>>, catalog: BaseIdCatalog): List<MergeIdConflict> =
        definitions.entries.filter { (key, mods) -> !catalog.contains(key.type, key.id) && mods.size > 1 }
            .map { (key, mods) -> MergeIdConflict(key.type, key.id, mods.distinct()) }
            .sortedWith(compareBy(MergeIdConflict::entityType, MergeIdConflict::id))

    private fun parse(text: String): JsonElement = runCatching { json.parseToJsonElement(cleanJson(text)) }
        .getOrElse { error("JSON 解析失败：${it.message ?: "格式无效"}") }

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
                    withoutComments.append(current)
                    if (escaped) escaped = false else if (current == '\\') escaped = true else if (current == '"') quoted = false
                    index++
                }
                current == '"' -> { quoted = true; withoutComments.append(current); index++ }
                current == '/' && next == '/' -> { while (index < source.length && source[index] !in "\r\n") index++ }
                current == '/' && next == '*' -> {
                    index += 2
                    while (index + 1 < source.length && !(source[index] == '*' && source[index + 1] == '/')) index++
                    index = (index + 2).coerceAtMost(source.length)
                }
                else -> { withoutComments.append(current); index++ }
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
                    if (escaped) escaped = false else if (current == '\\') escaped = true else if (current == '"') quoted = false
                    index++
                }
                current == '"' -> { quoted = true; result.append(current); index++ }
                current == ',' -> {
                    var next = index + 1
                    while (next < withoutComments.length && withoutComments[next].isWhitespace()) next++
                    if (next >= withoutComments.length || withoutComments[next] !in "}]") result.append(current)
                    index++
                }
                else -> { result.append(current); index++ }
            }
        }
        return result.toString()
    }

    private fun mergeFile(existing: JsonElement?, incoming: JsonElement, relative: String): JsonElement = when {
        existing == null -> incoming
        relative.substringAfterLast('/') == "sfx_config.json" -> incoming
        else -> deepMerge(existing, incoming, "")
    }

    private fun deepMerge(base: JsonElement, override: JsonElement, key: String): JsonElement = when {
        base is JsonObject && override is JsonObject -> buildJsonObject {
            base.forEach { (name, value) -> put(name, value) }
            override.forEach { (name, value) -> put(name, base[name]?.let { deepMerge(it, value, name) } ?: value) }
        }
        base is JsonArray && override is JsonArray && key in SMART_ARRAY_FIELDS -> mergeSmartArray(base, override)
        else -> override
    }

    private fun mergeSmartArray(base: JsonArray, override: JsonArray): JsonArray = buildJsonArray {
        val result = base.toMutableList()
        override.forEach { incoming ->
            val identity = arrayIdentity(incoming)
            val match = identity?.let { wanted -> result.indexOfFirst { arrayIdentity(it) == wanted } } ?: -1
            if (match >= 0) result[match] = deepMerge(result[match], incoming, "") else result += incoming
        }
        result.forEach(::add)
    }

    private fun arrayIdentity(element: JsonElement): String? {
        val value = element as? JsonObject ?: return null
        value["guid"]?.jsonPrimitive?.content?.let { return "guid:$it" }
        value["action"]?.let { return "action:$it" }
        value["condition"]?.let { return "condition:$it" }
        val title = value["result_title"]?.jsonPrimitive?.content
        val text = value["result_text"]?.jsonPrimitive?.content
        return if (title != null || text != null) "result:${title.orEmpty()}|${text.orEmpty()}" else null
    }

    private data class EntityKey(val type: String, val id: String)

    private fun collectDefinitions(roots: List<File>): Map<EntityKey, List<Int>> {
        val result = linkedMapOf<EntityKey, MutableList<Int>>()
        roots.forEachIndexed { index, root ->
            root.walkTopDown().filter { it.isFile && it.path.endsWith(".json", true) }.forEach { file ->
                entityKeys(file.relativeTo(root).invariantSeparatorsPath, file).forEach { key ->
                    result.getOrPut(key) { mutableListOf() }.add(index)
                }
            }
        }
        return result
    }

    private fun entityKeys(relative: String, file: File): List<EntityKey> {
        val parts = relative.split('/')
        if (parts.firstOrNull() != "config") return emptyList()
        val path = parts.drop(1)
        if (path.size == 1) return when (path[0]) {
            "cards.json" -> topLevelKeys(file, "cards")
            "tag.json" -> topLevelKeys(file, "tag")
            "over.json" -> topLevelKeys(file, "over")
            "rite_template_mappings.json" -> topLevelKeys(file, "rite_template_mappings")
            else -> emptyList()
        }
        if (path.size == 2 && path[1].endsWith(".json", true) && path[0] in FILE_ENTITY_TYPES) {
            return listOf(EntityKey(path[0], path[1].removeSuffix(".json")))
        }
        return emptyList()
    }

    private fun topLevelKeys(file: File, type: String): List<EntityKey> =
        (runCatching { parse(file.readText(StandardCharsets.UTF_8)) }.getOrNull() as? JsonObject)
            ?.keys?.map { EntityKey(type, it) }.orEmpty()

    private data class Remap(val integers: Map<Int, Int>, val strings: Map<String, String>) {
        val size: Int get() = integers.size + strings.size
    }

    private val EMPTY_REMAP = Remap(emptyMap(), emptyMap())

    private fun allocateRemaps(conflicts: List<MergeIdConflict>, definitions: Map<EntityKey, List<Int>>, modCount: Int): Map<Int, Remap> {
        val used = mutableMapOf<String, MutableSet<String>>()
        definitions.keys.forEach { used.getOrPut(it.type) { mutableSetOf() }.add(it.id) }
        val integerMaps = Array(modCount) { linkedMapOf<Int, Int>() }
        val stringMaps = Array(modCount) { linkedMapOf<String, String>() }
        conflicts.forEach { conflict ->
            val keeper = conflict.modIndexes.maxOrNull() ?: return@forEach
            var next = ID_STARTS[conflict.entityType] ?: 9_000_000
            conflict.modIndexes.sorted().forEach { modIndex ->
                if (modIndex == keeper) return@forEach
                val occupied = used.getOrPut(conflict.entityType) { mutableSetOf() }
                while (next.toString() in occupied) next++
                occupied += next.toString()
                conflict.id.toIntOrNull()?.let { integerMaps[modIndex][it] = next } ?: run { stringMaps[modIndex][conflict.id] = next.toString() }
                next++
            }
        }
        return (0 until modCount).associateWith { Remap(integerMaps[it], stringMaps[it]) }.filterValues { it.size > 0 }
    }

    private fun remapElement(element: JsonElement, remap: Remap): JsonElement = when (element) {
        is JsonObject -> buildJsonObject { element.forEach { (key, value) -> put(remap.strings[key] ?: key, remapElement(value, remap)) } }
        is JsonArray -> buildJsonArray { element.forEach { add(remapElement(it, remap)) } }
        is JsonPrimitive -> if (element.isString) {
            var value = element.content
            remap.strings.forEach { (old, new) -> value = value.replace(old, new) }
            JsonPrimitive(value)
        } else {
            val old = element.content.toIntOrNull()
            JsonPrimitive(old?.let { remap.integers[it]?.toString() } ?: element.content)
        }
        JsonNull -> JsonNull
    }

    private fun remapPath(relative: String, remap: Remap): String {
        var result = relative
        remap.strings.forEach { (old, new) -> result = result.replace("/${old}.", "/${new}.").replace("/${old}_", "/${new}_") }
        remap.integers.forEach { (old, new) -> result = result.replace("/${old}.", "/${new}.").replace("/${old}_", "/${new}_").replace("tag_$old", "tag_$new") }
        return result
    }

    private fun writeJson(root: File, relative: String, value: JsonElement) {
        val target = root.resolve(relative)
        require(target.parentFile.mkdirs() || target.parentFile.isDirectory) { "无法创建 JSON 目录" }
        target.writeText(json.encodeToString(JsonElement.serializer(), value), StandardCharsets.UTF_8)
    }

    private fun writeInfo(root: File, names: List<String>) {
        val content = buildJsonObject {
            put("name", JsonPrimitive("合并Mod - 自动生成"))
            put("description", JsonPrimitive("由 Mod 合并管理器自动生成。\n包含以下 mod 的合并内容：\n" + names.joinToString("\n") { "  - $it" }))
            put("tags", buildJsonArray { add(JsonPrimitive("Merged")) })
            put("version", JsonPrimitive(digest(names).take(16)))
            put("synthetic", JsonPrimitive(true))
        }
        root.resolve("Info.json").writeText(json.encodeToString(JsonObject.serializer(), content), StandardCharsets.UTF_8)
    }

    private fun digest(values: List<String>): String = MessageDigest.getInstance("SHA-256").digest(values.joinToString("\n").toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val SMART_ARRAY_FIELDS = setOf("settlement", "settlement_prior", "settlement_extre")
        val FILE_ENTITY_TYPES = setOf("rite", "event", "loot", "rite_template")
        val ID_STARTS = mapOf("cards" to 2_900_000, "tag_id" to 3_900_000, "rite" to 5_090_000, "event" to 5_390_000, "over" to 900, "loot" to 6_900_000, "rite_template" to 8_090_000, "rite_template_mappings" to 8_091_000)
    }
}
