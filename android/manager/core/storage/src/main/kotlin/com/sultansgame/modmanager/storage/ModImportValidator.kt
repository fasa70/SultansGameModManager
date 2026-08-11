package com.sultansgame.modmanager.storage

import com.sultansgame.modmanager.model.ModFile
import com.sultansgame.modmanager.model.ModFileKind
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.streams.toList

data class ValidatedModImport(
    val displayName: String,
    val files: List<ModFile>,
    val contentDigestSha256: String,
    val sizeBytes: Long,
)

class ModImportValidator {
    fun validate(root: Path, displayName: String = root.name): ValidatedModImport {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Mod 根目录不可读"
        }
        findManifest(root)
        val files = mutableListOf<ModFile>()
        val digests = mutableListOf<String>()
        val normalizedPaths = mutableSetOf<String>()
        val caseFoldedPaths = mutableSetOf<String>()
        var totalSize = 0L
        var entryCount = 0
        visit(root, "", 0, files, digests, normalizedPaths, caseFoldedPaths) { size ->
            entryCount += 1
            if (entryCount > MAXIMUM_MOD_ENTRY_COUNT) throw ImportValidationException("文件数量超出限制")
            totalSize = Math.addExact(totalSize, size)
            if (totalSize > MAXIMUM_MOD_TOTAL_SIZE_BYTES) throw ImportValidationException("总大小超出限制")
        }
        val contentDigest = MessageDigest.getInstance("SHA-256")
            .digest(digests.sorted().joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        return ValidatedModImport(
            displayName = ModDisplayNamePolicy.normalize(displayName) ?: ModDisplayNamePolicy.fallback(contentDigest),
            files = files.sortedBy { it.relativePath },
            contentDigestSha256 = contentDigest,
            sizeBytes = totalSize,
        )
    }

    private fun findManifest(root: Path): Path {
        val candidates = Files.list(root).use { entries ->
            entries.toList().filter { entry ->
                !Files.isSymbolicLink(entry) &&
                    Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) &&
                    entry.name.equals("info.json", ignoreCase = true)
            }
        }
        if (candidates.size != 1) throw ImportValidationException("根目录必须包含唯一的 Info.json")
        return candidates.single()
    }

    private fun visit(
        directory: Path,
        relativeDirectory: String,
        depth: Int,
        files: MutableList<ModFile>,
        digests: MutableList<String>,
        normalizedPaths: MutableSet<String>,
        caseFoldedPaths: MutableSet<String>,
        recordSize: (Long) -> Unit,
    ) {
        if (depth > com.sultansgame.modmanager.model.MAXIMUM_MOD_PATH_DEPTH) {
            throw ImportValidationException("目录深度超出限制")
        }
        Files.list(directory).use { entries ->
            entries.toList().sortedBy { it.name }.forEach { entry ->
                if (Files.isSymbolicLink(entry) || ModPathPolicy.isUnsafeComponent(entry.name)) {
                    throw ImportValidationException("包含不安全路径")
                }
                val relative = if (relativeDirectory.isEmpty()) entry.name else "$relativeDirectory/${entry.name}"
                when {
                    Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ->
                        visit(entry, relative, depth + 1, files, digests, normalizedPaths, caseFoldedPaths, recordSize)
                    Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) -> {
                        val size = entry.fileSize()
                        if (!ModPathPolicy.isSupportedSize(size, relative)) throw ImportValidationException("文件大小超出限制")
                        recordSize(size)
                        val normalized = ModPathPolicy.normalize(relative) ?: throw ImportValidationException("包含不安全路径")
                        if (!normalizedPaths.add(normalized) || !caseFoldedPaths.add(normalized.lowercase())) {
                            throw ImportValidationException("包含重复或大小写冲突路径")
                        }
                        val digest = sha256(entry)
                        digests += "$normalized\t$size\t$digest"
                        classify(normalized)?.let { files += ModFile(normalized, size, it) }
                    }
                    else -> throw ImportValidationException("包含非普通文件")
                }
            }
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun classify(path: String): ModFileKind? = when {
        path.endsWith(".json", ignoreCase = true) -> ModFileKind.Config
        path.endsWith(".png", ignoreCase = true) -> ModFileKind.Image
        path.endsWith(".wav", ignoreCase = true) || path.endsWith(".mp3", ignoreCase = true) || path.endsWith(".ogg", ignoreCase = true) -> ModFileKind.Audio
        else -> null
    }
}

object ModDisplayNamePolicy {
    private const val MAXIMUM_DISPLAY_NAME_LENGTH = 128

    fun normalize(raw: String): String? {
        val normalized = raw.filterNot(Char::isISOControl)
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
            .joinToString(" ")
            .take(MAXIMUM_DISPLAY_NAME_LENGTH)
            .trim()
        return normalized.takeIf(String::isNotEmpty)
    }

    fun fallback(cacheKey: String): String = "已缓存 Mod · ${cacheKey.take(8)}"
}

class ImportValidationException(message: String) : IllegalArgumentException(message)
