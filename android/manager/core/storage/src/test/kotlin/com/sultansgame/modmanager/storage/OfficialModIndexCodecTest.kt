package com.sultansgame.modmanager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfficialModIndexCodecTest {
    private val codec = OfficialModIndexCodec()

    @Test
    fun `decodes compact official dictionary shape`() {
        assertEquals(
            linkedMapOf("3489067376" to 3_489_067_376uL, "local-copy" to 42uL),
            codec.decode("{\"3489067376\":3489067376,\"local-copy\":42}".toByteArray()),
        )
    }

    @Test
    fun `accepts JSON whitespace and valid Unicode`() {
        assertEquals(
            linkedMapOf("未来视😀" to ULong.MAX_VALUE, "原生😀" to 7uL),
            codec.decode(
                "  { \"\\u672a来视\\uD83D\\uDE00\" : 18446744073709551615, \"原生😀\": 7 }\n".toByteArray(),
            ),
        )
    }

    @Test
    fun `accepts an empty index`() {
        assertEquals(emptyMap<String, ULong>(), codec.decode("{}".toByteArray()))
    }

    @Test
    fun `rejects invalid UTF-8`() {
        assertInvalid(byteArrayOf('{'.code.toByte(), '}'.code.toByte(), 0x80.toByte()))
    }

    @Test
    fun `rejects duplicate directory names after decoding escapes`() {
        assertInvalid("{\"mod\":1,\"\\u006dod\":2}")
    }

    @Test
    fun `rejects unsafe directory names`() {
        listOf("", ".", "..", "parent/child", "parent\\child", "nul\\u0000name").forEach { name ->
            assertInvalid("{\"$name\":1}")
        }
    }

    @Test
    fun `rejects values outside positive unsigned decimal integers`() {
        listOf(
            "0",
            "00",
            "01",
            "-1",
            "1.0",
            "1e2",
            "18446744073709551616",
            "\"1\"",
            "null",
            "true",
        ).forEach { value ->
            assertInvalid("{\"mod\":$value}")
        }
    }

    @Test
    fun `rejects extensions not emitted by official compact serializer`() {
        listOf(
            "{\"mod\":1,}",
            "{// comment\n\"mod\":1}",
            "{/* comment */\"mod\":1}",
            "{\"mod\":1} trailing",
            "[\"mod\",1]",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `rejects invalid Unicode surrogate escapes`() {
        listOf(
            "{\"\\uD83D\":1}",
            "{\"\\uDE00\":1}",
            "{\"\\uD83D\\u0041\":1}",
        ).forEach(::assertInvalid)
    }

    private fun assertInvalid(text: String) = assertInvalid(text.toByteArray())

    private fun assertInvalid(bytes: ByteArray) {
        assertThrows(InvalidOfficialModIndexException::class.java) {
            codec.decode(bytes)
        }
    }
}
