package com.sultansgame.modmanager.platform.storage

import android.system.Os
import android.system.OsConstants
import com.sultansgame.modmanager.model.CacheSource
import com.sultansgame.modmanager.model.CachedMod
import com.sultansgame.modmanager.model.CachedModState
import com.sultansgame.modmanager.storage.ImportValidationException
import com.sultansgame.modmanager.storage.InfoJsonValidator
import com.sultansgame.modmanager.storage.InvalidManifestException
import com.sultansgame.modmanager.storage.MAXIMUM_MOD_ENTRY_COUNT
import com.sultansgame.modmanager.storage.MAXIMUM_MOD_TOTAL_SIZE_BYTES
import com.sultansgame.modmanager.storage.ModPathPolicy
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

data class AndroidValidatedMod(
    val name: String,
    val digest: String,
    val sizeBytes: Long,
)

sealed interface CachedModDeletionResult {
    data object Deleted : CachedModDeletionResult
    data object NotFound : CachedModDeletionResult
    data class Rejected(val reason: String) : CachedModDeletionResult
    data class Failed(val reason: String) : CachedModDeletionResult
}

class AndroidPrivateModCache(private val cacheRoot: File) {
    private val manifestValidator = InfoJsonValidator()

    fun listCached(): List<CachedMod> {
        if (!cacheRoot.isDirectory) return emptyList()
        return cacheRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') && it.name.matches(Regex("[0-9a-f]{64}")) }
            ?.sortedBy { it.name }
            ?.mapNotNull { directory ->
                runCatching {
                    val validated = validate(directory)
                    CachedMod(
                        cacheKey = directory.name,
                        contentDigestSha256 = directory.name,
                        displayName = validated.name,
                        source = CacheSource.SafTree,
                        sizeBytes = validated.sizeBytes,
                        importedAtEpochMillis = directory.lastModified(),
                        state = CachedModState.Cached,
                    )
                }.getOrNull()
            }
            .orEmpty()
    }

    fun clear() {
        cacheRoot.deleteRecursively()
    }

    fun deleteCached(cacheKey: String): CachedModDeletionResult {
        if (!cacheKey.matches(CACHE_KEY_REGEX)) return CachedModDeletionResult.Rejected("Mod 缓存标识无效。")
        if (cacheRoot.exists() && isSymbolicLink(cacheRoot)) return CachedModDeletionResult.Rejected("私有缓存目录不可安全访问。")
        val target = File(cacheRoot, cacheKey)
        if (!target.exists()) return CachedModDeletionResult.NotFound
        if (!target.isDirectory || isSymbolicLink(target)) {
            return CachedModDeletionResult.Rejected("Mod 缓存目录不可安全访问。")
        }
        if (containsSymbolicLink(target)) {
            return CachedModDeletionResult.Rejected("Mod 缓存包含不安全链接。")
        }
        return try {
            if (!deleteRecursivelyWithoutLinks(target) || target.exists()) {
                CachedModDeletionResult.Failed("无法完全删除私有缓存目录。")
            } else {
                CachedModDeletionResult.Deleted
            }
        } catch (error: SecurityException) {
            CachedModDeletionResult.Failed("没有删除私有缓存的权限。")
        } catch (error: Exception) {
            CachedModDeletionResult.Failed(error.message ?: "无法删除私有缓存目录。")
        }
    }

    fun recoverInterruptedImports() {
        cacheRoot.listFiles()
            ?.filter { it.name.startsWith('.') && it.name.endsWith(".partial") }
            ?.forEach(File::deleteRecursively)
    }
    fun importDirectory(sourceRoot: File, source: CacheSource): CachedMod =
        importDirectories(listOf(sourceRoot), source).single()

    fun importDirectories(sourceRoots: List<File>, source: CacheSource): List<CachedMod> {
        if (sourceRoots.isEmpty()) return emptyList()
        val validated = sourceRoots.map { it to validateDirectory(it) }
            .distinctBy { it.second.digest }
        if (!cacheRoot.mkdirs() && !cacheRoot.isDirectory) throw ImportValidationException("无法创建私有缓存目录")
        val pending = mutableListOf<PendingImport>()
        val committed = mutableListOf<File>()
        try {
            validated.forEach { (sourceRoot, expected) ->
                val staging = File(cacheRoot, ".${UUID.randomUUID()}.partial")
                pending += PendingImport(sourceRoot, expected, staging)
                copyDirectory(sourceRoot, staging, 0)
                val copied = validateDirectory(staging)
                if (copied.digest != expected.digest || copied.sizeBytes != expected.sizeBytes) {
                    throw ImportValidationException("导入内容在复制期间发生变化")
                }
            }
            return pending.map { item ->
                val destination = File(cacheRoot, item.validated.digest)
                if (!destination.exists()) {
                    if (!item.staging.renameTo(destination)) throw ImportValidationException("无法提交私有缓存")
                    committed += destination
                } else {
                    item.staging.deleteRecursively()
                }
                CachedMod(
                    cacheKey = item.validated.digest,
                    contentDigestSha256 = item.validated.digest,
                    displayName = item.validated.name,
                    source = source,
                    sizeBytes = item.validated.sizeBytes,
                    importedAtEpochMillis = System.currentTimeMillis(),
                    state = CachedModState.Cached,
                )
            }
        } catch (error: Exception) {
            pending.forEach { it.staging.deleteRecursively() }
            committed.forEach { it.deleteRecursively() }
            throw error
        }
    }

    private data class PendingImport(
        val sourceRoot: File,
        val validated: AndroidValidatedMod,
        val staging: File,
    )


    fun validateDirectory(sourceRoot: File): AndroidValidatedMod {
        if (!sourceRoot.isDirectory || isSymbolicLink(sourceRoot)) throw ImportValidationException("Mod 根目录不可读")
        return validate(sourceRoot)
    }

    private fun copyDirectory(source: File, target: File, depth: Int) {
        if (depth > com.sultansgame.modmanager.model.MAXIMUM_MOD_PATH_DEPTH) throw ImportValidationException("目录深度超出限制")
        if (!target.mkdirs() && !target.isDirectory) throw ImportValidationException("无法创建私有导入目录")
        source.listFiles()?.sortedBy { it.name }?.forEach { entry ->
            if (isSymbolicLink(entry) || ModPathPolicy.isUnsafeComponent(entry.name)) throw ImportValidationException("包含不安全路径")
            val destination = File(target, entry.name)
            when {
                entry.isDirectory -> copyDirectory(entry, destination, depth + 1)
                entry.isFile -> entry.inputStream().use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                else -> throw ImportValidationException("包含非普通文件")
            }
        } ?: throw ImportValidationException("无法读取导入目录")
    }

    private fun validate(root: File): AndroidValidatedMod {
        val manifest = root.listFiles()
            ?.filter { it.isFile && it.name.equals("info.json", ignoreCase = true) }
            ?.singleOrNull()
            ?: throw ImportValidationException("根目录必须包含唯一的 Info.json")
        val parsedManifest = try {
            manifestValidator.parse(manifest.readBytes())
        } catch (error: InvalidManifestException) {
            throw ImportValidationException(error.message ?: "Info.json 无效")
        }
        val entries = mutableListOf<String>()
        var count = 0
        var total = 0L
        fun scan(directory: File, prefix: String, depth: Int) {
            if (depth > com.sultansgame.modmanager.model.MAXIMUM_MOD_PATH_DEPTH) throw ImportValidationException("目录深度超出限制")
            if (++count > MAXIMUM_MOD_ENTRY_COUNT) throw ImportValidationException("文件或目录数量超出限制")
            directory.listFiles()?.sortedBy { it.name }?.forEach { entry ->
                if (isSymbolicLink(entry) || ModPathPolicy.isUnsafeComponent(entry.name)) throw ImportValidationException("包含不安全路径")
                val relative = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
                when {
                    entry.isDirectory -> scan(entry, relative, depth + 1)
                    entry.isFile -> {
                        if (++count > MAXIMUM_MOD_ENTRY_COUNT) throw ImportValidationException("文件数量超出限制")
                        val size = entry.length()
                        if (!ModPathPolicy.isSupportedSize(size, relative)) throw ImportValidationException("文件大小超出限制")
                        total = Math.addExact(total, size)
                        if (total > MAXIMUM_MOD_TOTAL_SIZE_BYTES) throw ImportValidationException("总大小超出限制")
                        entries += "$relative\t$size\t${sha256(entry)}"
                    }
                    else -> throw ImportValidationException("包含非普通文件")
                }
            } ?: throw ImportValidationException("无法读取导入目录")
        }
        scan(root, "", 0)
        val digest = MessageDigest.getInstance("SHA-256").digest(entries.sorted().joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        return AndroidValidatedMod(parsedManifest.name, digest, total)
    }

    private fun containsSymbolicLink(directory: File): Boolean {
        if (isSymbolicLink(directory)) return true
        val children = directory.listFiles() ?: return true
        return children.any { child ->
            isSymbolicLink(child) || (child.isDirectory && containsSymbolicLink(child))
        }
    }

    private fun deleteRecursivelyWithoutLinks(directory: File): Boolean {
        if (isSymbolicLink(directory)) return false
        val children = directory.listFiles() ?: return false
        for (child in children) {
            val deleted = when {
                isSymbolicLink(child) -> child.delete()
                child.isDirectory -> deleteRecursivelyWithoutLinks(child)
                child.isFile -> child.delete()
                else -> false
            }
            if (!deleted) return false
        }
        return directory.delete()
    }

    private companion object {
        val CACHE_KEY_REGEX = Regex("[0-9a-f]{64}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching {
        OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    }.getOrDefault(true)
}
