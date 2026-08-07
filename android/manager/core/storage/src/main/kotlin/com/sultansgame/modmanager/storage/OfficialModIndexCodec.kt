package com.sultansgame.modmanager.storage

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class OfficialModIndexCodec {
    fun decode(bytes: ByteArray): Map<String, ULong> {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw InvalidOfficialModIndexException("mods.json 不是有效 UTF-8")
        }
        return OfficialModIndexReader(text).read()
    }
}

class InvalidOfficialModIndexException(message: String) : IllegalArgumentException(message)

private class OfficialModIndexReader(private val source: String) {
    private var index = 0

    fun read(): Map<String, ULong> {
        skipWhitespace()
        expect('{')
        val entries = linkedMapOf<String, ULong>()
        skipWhitespace()
        if (consume('}')) return finish(entries)
        while (true) {
            val directoryName = readString()
            if (directoryName in entries) fail("mods.json 包含重复目录：$directoryName")
            if (ModPathPolicy.isUnsafeComponent(directoryName)) fail("mods.json 包含不安全目录名")
            skipWhitespace()
            expect(':')
            skipWhitespace()
            entries[directoryName] = readPublishedFileId()
            skipWhitespace()
            if (consume('}')) return finish(entries)
            expect(',')
            skipWhitespace()
            if (peek() == '}') fail("mods.json 不允许尾随逗号")
        }
    }

    private fun finish(entries: Map<String, ULong>): Map<String, ULong> {
        skipWhitespace()
        if (index != source.length) fail("mods.json 根对象后存在额外内容")
        return entries
    }

    private fun readPublishedFileId(): ULong {
        val start = index
        if (peek() !in '0'..'9') fail("mods.json 的 PublishedFileId 必须是十进制正整数")
        if (peek() == '0') {
            index++
            if (peek()?.isDigit() == true) fail("mods.json 的 PublishedFileId 不允许前导零")
        } else {
            while (peek()?.isDigit() == true) index++
        }
        val raw = source.substring(start, index)
        return raw.toULongOrNull()
            ?.takeIf { it > 0uL }
            ?: fail("mods.json 的 PublishedFileId 超出有效范围")
    }

    private fun readString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            when (val character = source[index++]) {
                '"' -> return result.toString()
                '\\' -> appendEscape(result)
                else -> {
                    if (character.code <= 0x1f) fail("mods.json 字符串含控制字符")
                    appendUnescapedCharacter(result, character)
                }
            }
        }
        fail("mods.json 字符串未结束")
    }

    private fun appendUnescapedCharacter(result: StringBuilder, character: Char) {
        when {
            character.isHighSurrogate() -> {
                val second = next()
                if (!second.isLowSurrogate()) fail("mods.json 包含无效代理对")
                result.append(character).append(second)
            }
            character.isLowSurrogate() -> fail("mods.json 包含无效代理对")
            else -> result.append(character)
        }
    }

    private fun appendEscape(result: StringBuilder) {
        when (val escaped = next()) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> appendUnicodeEscape(result)
            else -> fail("mods.json 包含无效转义")
        }
    }

    private fun appendUnicodeEscape(result: StringBuilder) {
        val first = readUnicodeCodeUnit()
        when {
            first.isHighSurrogate() -> {
                if (!consume('\\') || !consume('u')) fail("mods.json 包含不完整的代理对")
                val second = readUnicodeCodeUnit()
                if (!second.isLowSurrogate()) fail("mods.json 包含无效代理对")
                result.append(first).append(second)
            }
            first.isLowSurrogate() -> fail("mods.json 包含无效代理对")
            else -> result.append(first)
        }
    }

    private fun readUnicodeCodeUnit(): Char {
        if (index + 4 > source.length) fail("mods.json 包含无效 Unicode 转义")
        val value = source.substring(index, index + 4).toIntOrNull(16)
            ?: fail("mods.json 包含无效 Unicode 转义")
        index += 4
        return value.toChar()
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') index++
    }

    private fun expect(expected: Char) {
        if (!consume(expected)) fail("mods.json 预期 $expected")
    }

    private fun consume(expected: Char): Boolean {
        if (peek() != expected) return false
        index++
        return true
    }

    private fun peek(): Char? = source.getOrNull(index)

    private fun next(): Char = source.getOrNull(index++) ?: fail("mods.json 意外结束")

    private fun fail(message: String): Nothing = throw InvalidOfficialModIndexException(message)
}
