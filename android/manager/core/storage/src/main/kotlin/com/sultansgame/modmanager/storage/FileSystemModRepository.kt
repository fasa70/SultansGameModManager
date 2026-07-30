package com.sultansgame.modmanager.storage

import com.sultansgame.modmanager.model.MAXIMUM_MOD_FILE_SIZE_BYTES
import com.sultansgame.modmanager.model.MAXIMUM_MOD_PATH_DEPTH
import com.sultansgame.modmanager.model.ModFile
import com.sultansgame.modmanager.model.ModFileKind
import com.sultansgame.modmanager.model.ModRecord
import com.sultansgame.modmanager.model.ModScanResult
import com.sultansgame.modmanager.model.RejectedEntry
import com.sultansgame.modmanager.model.RejectionReason
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.streams.toList

interface ModRepository {
    fun scan(root: Path): ModScanResult
    fun read(file: IndexedModFile, maximumSizeBytes: Long = MAXIMUM_MOD_FILE_SIZE_BYTES): ByteArray?
}

data class IndexedModFile(
    val absolutePath: Path,
    val relativePath: String,
    val sizeBytes: Long,
)

class FileSystemModRepository : ModRepository {
    override fun scan(root: Path): ModScanResult {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            return ModScanResult(emptyList(), emptyList())
        }

        val rejected = mutableListOf<RejectedEntry>()
        val mods = Files.list(root).use { entries ->
            entries.toList().sortedBy { it.name }.mapNotNull { candidate ->
                val name = candidate.name
                if (!isSafeComponent(name)) {
                    rejected += RejectedEntry(candidate.toString(), RejectionReason.InvalidName)
                    return@mapNotNull null
                }
                if (Files.isSymbolicLink(candidate) ||
                    !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                ) {
                    rejected += RejectedEntry(candidate.toString(), RejectionReason.InvalidModDirectory)
                    return@mapNotNull null
                }

                val files = mutableListOf<ModFile>()
                scanDirectory(candidate, candidate, "", 0, files, rejected)
                ModRecord(name, files.sortedBy { it.relativePath })
            }.toList()
        }
        return ModScanResult(mods, rejected.sortedWith(compareBy({ it.path }, { it.reason.name })))
    }

    override fun read(file: IndexedModFile, maximumSizeBytes: Long): ByteArray? {
        if (file.sizeBytes > maximumSizeBytes || Files.isSymbolicLink(file.absolutePath)) {
            return null
        }
        return runCatching {
            val attributes = Files.readAttributes(
                file.absolutePath,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!attributes.isRegularFile || attributes.size() != file.sizeBytes || attributes.size() > maximumSizeBytes) {
                return null
            }
            Files.readAllBytes(file.absolutePath).takeIf { it.size.toLong() == file.sizeBytes }
        }.getOrNull()
    }

    private fun scanDirectory(
        modRoot: Path,
        directory: Path,
        relativeDirectory: String,
        depth: Int,
        files: MutableList<ModFile>,
        rejected: MutableList<RejectedEntry>,
    ) {
        if (depth > MAXIMUM_MOD_PATH_DEPTH) {
            rejected += RejectedEntry(directory.toString(), RejectionReason.DepthExceeded)
            return
        }
        Files.list(directory).use { entries ->
            entries.toList().sortedBy { it.name }.forEach { entry ->
                val name = entry.name
                if (!isSafeComponent(name)) {
                    rejected += RejectedEntry(entry.toString(), RejectionReason.InvalidName)
                    return@forEach
                }
                val relative = if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
                when {
                    Files.isSymbolicLink(entry) -> {
                        rejected += RejectedEntry(entry.toString(), RejectionReason.SymbolicLink)
                    }
                    Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> {
                        scanDirectory(modRoot, entry, relative, depth + 1, files, rejected)
                    }
                    Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) -> {
                        val size = runCatching { entry.fileSize() }.getOrElse {
                            rejected += RejectedEntry(entry.toString(), RejectionReason.ReadFailure)
                            return@forEach
                        }
                        if (!ModPathPolicy.isSupportedSize(size, relative)) {
                            rejected += RejectedEntry(entry.toString(), RejectionReason.FileTypeOrSize)
                            return@forEach
                        }
                        classify(relative)?.let { kind -> files += ModFile(relative, size, kind) }
                    }
                    else -> rejected += RejectedEntry(entry.toString(), RejectionReason.FileTypeOrSize)
                }
            }
        }
    }

    private fun classify(relativePath: String): ModFileKind? = when {
        relativePath.endsWith(".json") -> ModFileKind.Config
        relativePath.endsWith(".png") -> ModFileKind.Image
        relativePath.endsWith(".wav") || relativePath.endsWith(".mp3") || relativePath.endsWith(".ogg") -> ModFileKind.Audio
        else -> null
    }

    private fun isSafeComponent(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == NUL_CHARACTER }

    private companion object {
        const val NUL_CHARACTER: Char = 0.toChar()
    }
}
