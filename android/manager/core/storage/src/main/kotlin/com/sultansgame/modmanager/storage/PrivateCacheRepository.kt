package com.sultansgame.modmanager.storage

import com.sultansgame.modmanager.model.CacheSource
import com.sultansgame.modmanager.model.CachedMod
import com.sultansgame.modmanager.model.CachedModState
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.streams.toList

class PrivateCacheRepository(
    private val cacheRoot: Path,
    private val validator: ModImportValidator = ModImportValidator(),
) {
    fun importDirectory(sourceRoot: Path, source: CacheSource): CachedMod {
        cacheRoot.createDirectories()
        val staging = cacheRoot.resolve(".${UUID.randomUUID()}.partial")
        try {
            copyDirectory(sourceRoot, staging)
            val validated = validator.validate(staging)
            val destination = cacheRoot.resolve(validated.contentDigestSha256)
            if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                moveAtomically(staging, destination)
            } else {
                deleteRecursively(staging)
            }
            return CachedMod(
                cacheKey = validated.contentDigestSha256,
                contentDigestSha256 = validated.contentDigestSha256,
                displayName = validated.manifest.name,
                source = source,
                sizeBytes = validated.sizeBytes,
                importedAtEpochMillis = System.currentTimeMillis(),
                state = CachedModState.Cached,
            )
        } catch (error: Exception) {
            deleteRecursively(staging)
            throw error
        }
    }

    fun recoverInterruptedImports() {
        if (!Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) return
        Files.list(cacheRoot).use { entries ->
            entries.toList()
                .filter { it.name.startsWith(".") && it.name.endsWith(".partial") }
                .forEach(::deleteRecursively)
        }
    }

    private fun copyDirectory(source: Path, target: Path) {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw ImportValidationException("Mod 根目录不可读")
        }
        target.createDirectories()
        Files.list(source).use { entries ->
            entries.toList().sortedBy { it.name }.forEach { entry ->
                if (Files.isSymbolicLink(entry) || ModPathPolicy.isUnsafeComponent(entry.name)) {
                    throw ImportValidationException("包含不安全路径")
                }
                val destination = target.resolve(entry.name)
                when {
                    Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> copyDirectory(entry, destination)
                    Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) -> {
                        if (!ModPathPolicy.isSupportedSize(Files.size(entry), entry.name)) {
                            throw ImportValidationException("文件大小超出限制")
                        }
                        Files.copy(entry, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                    else -> throw ImportValidationException("包含非普通文件")
                }
            }
        }
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
