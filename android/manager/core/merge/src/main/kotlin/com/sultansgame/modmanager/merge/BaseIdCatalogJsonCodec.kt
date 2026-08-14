package com.sultansgame.modmanager.merge

import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json

class BaseIdCatalogJsonCodec(
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = false },
) {
    fun encode(catalog: BaseIdCatalog): String = json.encodeToString(BaseIdCatalog.serializer(), catalog)

    fun decode(text: String): BaseIdCatalog {
        require(!hasDuplicateObjectKeys(text)) { "catalog 包含重复 JSON key" }
        return json.decodeFromString(BaseIdCatalog.serializer(), text)
    }

    fun write(catalog: BaseIdCatalog, file: File) {
        require(file.parentFile.mkdirs() || file.parentFile.isDirectory) { "无法创建 catalog 目录" }
        file.writeText(encode(catalog), StandardCharsets.UTF_8)
    }
}

private fun hasDuplicateObjectKeys(text: String): Boolean {
    val stack = ArrayDeque<MutableSet<String>>()
    var index = 0
    fun skipWhitespace() { while (index < text.length && text[index].isWhitespace()) index++ }
    fun string(): String {
        require(index < text.length && text[index++] == '"')
        val value = StringBuilder()
        while (index < text.length) {
            when (val ch = text[index++]) {
                '"' -> return value.toString()
                '\\' -> {
                    require(index < text.length)
                    when (val escaped = text[index++]) {
                        'u' -> { val hex = text.substring(index, index + 4); value.append(hex.toInt(16).toChar()); index += 4 }
                        else -> value.append(escaped)
                    }
                }
                else -> value.append(ch)
            }
        }
        error("catalog 字符串未闭合")
    }
    fun value() {
        skipWhitespace()
        when (text.getOrNull(index)) {
            '{' -> {
                index++; stack.addLast(mutableSetOf()); skipWhitespace()
                if (text.getOrNull(index) == '}') { index++; stack.removeLast(); return }
                while (true) {
                    skipWhitespace(); val key = string(); if (!stack.last().add(key)) throw IllegalArgumentException("duplicate")
                    skipWhitespace(); require(text.getOrNull(index++) == ':'); value(); skipWhitespace()
                    when (text.getOrNull(index++)) { '}' -> { stack.removeLast(); return }; ',' -> Unit; else -> error("catalog object") }
                }
            }
            '[' -> { index++; skipWhitespace(); if (text.getOrNull(index) == ']') { index++; return }; while (true) { value(); skipWhitespace(); when (text.getOrNull(index++)) { ']' -> return; ',' -> Unit; else -> error("catalog array") } } }
            '"' -> { string() }
            else -> while (index < text.length && text[index] !in ",]}" && !text[index].isWhitespace()) index++
        }
    }
    return try { value(); skipWhitespace(); index != text.length } catch (_: IllegalArgumentException) { true }
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
