package com.sultansgame.modmanager.storage

import com.sultansgame.modmanager.model.CacheSource
import com.sultansgame.modmanager.model.MAXIMUM_MOD_FILE_SIZE_BYTES
import com.sultansgame.modmanager.model.MAXIMUM_MOD_MEDIA_FILE_SIZE_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class ModImportValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `accepts arbitrary info json content and computes stable digest`() {
        val root = temporaryFolder.newFolder("sample").toPath()
        root.resolve("info.json").writeBytes(byteArrayOf('{'.code.toByte(), 0.toByte(), '}'.code.toByte()))
        root.resolve("config").createDirectories().resolve("cards.json").writeText("{}")

        val first = ModImportValidator().validate(root, "Sample")
        val second = ModImportValidator().validate(root, "Sample")

        assertEquals("Sample", first.displayName)
        assertEquals(first.contentDigestSha256, second.contentDigestSha256)
    }

    @Test
    fun `requires a root manifest`() {
        val root = temporaryFolder.newFolder("sample").toPath()
        root.resolve("config").createDirectories().resolve("cards.json").writeText("{}")

        assertThrows(ImportValidationException::class.java) {
            ModImportValidator().validate(root)
        }
    }

    @Test
    fun `accepts case insensitive manifest names without parsing content`() {
        val root = temporaryFolder.newFolder("sample").toPath()
        root.resolve("Info.JSON").writeText("anything")

        assertEquals("Sample", ModImportValidator().validate(root, "Sample").displayName)
    }

    @Test
    fun `accepts media larger than the configuration size limit`() {
        val root = temporaryFolder.newFolder("sample").toPath()
        root.resolve("info.json").writeText("not json")
        val audio = root.resolve("bgm").createDirectories().resolve("theme.wav")
        Files.write(audio, ByteArray((MAXIMUM_MOD_FILE_SIZE_BYTES + 1).toInt()))

        val validated = ModImportValidator().validate(root)
        assertTrue(validated.sizeBytes > MAXIMUM_MOD_FILE_SIZE_BYTES)
        assertEquals("bgm/theme.wav", validated.files.single { it.relativePath == "bgm/theme.wav" }.relativePath)
    }

    @Test
    fun `keeps media and non-media size limits distinct`() {
        assertTrue(ModPathPolicy.isSupportedSize(MAXIMUM_MOD_FILE_SIZE_BYTES + 1, "bgm/theme.wav"))
        assertFalse(ModPathPolicy.isSupportedSize(MAXIMUM_MOD_FILE_SIZE_BYTES + 1, "config/cards.json"))
        assertFalse(ModPathPolicy.isSupportedSize(MAXIMUM_MOD_MEDIA_FILE_SIZE_BYTES + 1, "image/card.png"))
    }

    @Test
    fun `cache deduplicates identical validated content`() {
        val source = temporaryFolder.newFolder("source").toPath()
        source.resolve("info.json").writeText("invalid")
        val cache = temporaryFolder.newFolder("cache").toPath()
        val repository = PrivateCacheRepository(cache)

        val first = repository.importDirectory(source, CacheSource.SafTree, "Sample")
        val second = repository.importDirectory(source, CacheSource.SafTree, "Sample")

        assertEquals(first.cacheKey, second.cacheKey)
        assertEquals(1, Files.list(cache).use { it.count() })
    }
}
