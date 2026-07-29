package com.sultansgame.modmanager.storage

import com.sultansgame.modmanager.model.CacheSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ModImportValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `accepts uppercase Info json and computes stable digest`() {
        val root = temporaryFolder.newFolder("sample").toPath()
        root.resolve("Info.json").writeText("{ // sample\n \"name\": \"Sample\", }")
        root.resolve("config").createDirectories().resolve("cards.json").writeText("{}")

        val first = ModImportValidator().validate(root)
        val second = ModImportValidator().validate(root)

        assertEquals("Sample", first.manifest.name)
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
    fun `cache deduplicates identical validated content`() {
        val source = temporaryFolder.newFolder("source").toPath()
        source.resolve("info.json").writeText("{\"name\":\"Sample\"}")
        source.resolve("config").createDirectories().resolve("cards.json").writeText("{}")
        val cache = temporaryFolder.newFolder("cache").toPath()
        val repository = PrivateCacheRepository(cache)

        val first = repository.importDirectory(source, CacheSource.SafTree)
        val second = repository.importDirectory(source, CacheSource.SafTree)

        assertEquals(first.cacheKey, second.cacheKey)
        assertEquals(1, Files.list(cache).use { it.count() })
    }
}
