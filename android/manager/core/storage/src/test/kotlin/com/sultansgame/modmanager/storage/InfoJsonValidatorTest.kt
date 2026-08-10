package com.sultansgame.modmanager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InfoJsonValidatorTest {
    private val validator = InfoJsonValidator()

    @Test
    fun `accepts official filename content conventions`() {
        val manifest = validator.parse(
            """
            {
              // official samples use comments
              "name": "示例 Mod",
              "description": "说明",
              "tags": ["Utilities",],
            }
            """.trimIndent().toByteArray(),
        )

        assertEquals("示例 Mod", manifest.name)
        assertEquals(listOf("Utilities"), manifest.tags)
    }

    @Test
    fun `accepts arbitrary tags`() {
        val manifest = validator.parse(
            """
            {
              "name": "示例 Mod",
              "tags": ["任意标签", "", "任意标签"],
            }
            """.trimIndent().toByteArray(),
        )

        assertEquals(listOf("任意标签", "", "任意标签"), manifest.tags)
    }

    @Test
    fun `rejects duplicate root fields`() {
        assertThrows(InvalidManifestException::class.java) {
            validator.parse("{\"name\":\"a\",\"name\":\"b\"}".toByteArray())
        }
    }

    @Test
    fun `rejects invalid utf8`() {
        assertThrows(InvalidManifestException::class.java) {
            validator.parse(byteArrayOf('{'.code.toByte(), '}'.code.toByte(), 0x80.toByte()))
        }
    }
}
