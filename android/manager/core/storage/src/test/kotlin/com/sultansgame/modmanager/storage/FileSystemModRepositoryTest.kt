package com.sultansgame.modmanager.storage

import com.sultansgame.modmanager.model.MAXIMUM_MOD_FILE_SIZE_BYTES
import com.sultansgame.modmanager.model.ModFileKind
import com.sultansgame.modmanager.model.RejectionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FileSystemModRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val repository = FileSystemModRepository()

    @Test
    fun `scan preserves native directory and file ordering`() {
        val root = temporaryFolder.newFolder("Mod").toPath()
        root.resolve("z-last").createDirectories().resolve("config.json").writeText("{}")
        root.resolve("a-first").createDirectories().apply {
            resolve("image.png").writeText("image")
            resolve("nested").createDirectories().resolve("event.json").writeText("{}")
        }

        val result = repository.scan(root)

        assertEquals(listOf("a-first", "z-last"), result.mods.map { it.directoryName })
        assertEquals(
            listOf("image.png", "nested/event.json"),
            result.mods.first().files.map { it.relativePath },
        )
        assertEquals(ModFileKind.Config, result.mods.first().files.last().kind)
    }

    @Test
    fun `scan rejects symlink oversized file and excessive depth`() {
        val root = temporaryFolder.newFolder("Mod").toPath()
        val mod = root.resolve("sample").createDirectories()
        Files.write(mod.resolve("large.json"), ByteArray((MAXIMUM_MOD_FILE_SIZE_BYTES + 1).toInt()))
        val tooDeep = (1..9).fold(mod) { directory, index ->
            directory.resolve("d$index").createDirectories()
        }
        tooDeep.resolve("data.json").writeText("{}")
        val target = temporaryFolder.newFile("target.json").toPath()
        runCatching { Files.createSymbolicLink(mod.resolve("link.json"), target) }

        val result = repository.scan(root)
        val reasons = result.rejectedEntries.map { it.reason }.toSet()

        assertTrue(RejectionReason.FileTypeOrSize in reasons)
        assertTrue(RejectionReason.DepthExceeded in reasons)
    }
}
