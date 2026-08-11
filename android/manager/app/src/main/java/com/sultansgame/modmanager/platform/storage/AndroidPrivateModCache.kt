package com.sultansgame.modmanager.platform.storage

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.sultansgame.modmanager.model.CacheSource
import com.sultansgame.modmanager.model.CachedMod
import com.sultansgame.modmanager.model.CachedModState
import com.sultansgame.modmanager.storage.ImportValidationException
import com.sultansgame.modmanager.storage.InfoJsonValidator
import com.sultansgame.modmanager.storage.MAXIMUM_MOD_ENTRY_COUNT
import com.sultansgame.modmanager.storage.ModDisplayNamePolicy
import com.sultansgame.modmanager.storage.ModPathPolicy
import com.sultansgame.modmanager.storage.StorageBudget
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

class AndroidPrivateModCache(
    private val cacheRoot: File,
    private val budget: StorageBudget = StorageBudget.UNBOUNDED,
    private val context: Context? = null,
) {
    constructor(cacheRoot: File, context: Context) : this(cacheRoot, StorageBudget.UNBOUNDED, context)

    private val manifestValidator = InfoJsonValidator()
    private val namePreferences = context?.getSharedPreferences(NAME_PREFERENCES, Context.MODE_PRIVATE)

    fun listCached(): List<CachedMod> {
        if (!cacheRoot.isDirectory) return emptyList()
        val directories = cacheRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') && it.name.matches(CACHE_KEY_REGEX) }
            .orEmpty()
        val names = namePreferences?.let { preferences ->
            val currentKeys = directories.mapTo(mutableSetOf(), File::getName)
            val editor = preferences.edit()
            preferences.all.keys.filterNot(currentKeys::contains).forEach(editor::remove)
            editor.apply()
            directories.associate { directory -> directory.name to preferences.getString(directory.name, null) }
        }.orEmpty()
        return directories.sortedBy(File::getName).mapNotNull { directory ->
            runCatching {
                val validated = validate(directory, directory.name)
                val displayName = names[directory.name] ?: migrateLegacyDisplayName(directory, directory.name)
                CachedMod(
                    cacheKey = directory.name,
                    contentDigestSha256 = directory.name,
                    displayName = displayName,
                    source = CacheSource.SafTree,
                    sizeBytes = validated.sizeBytes,
                    importedAtEpochMillis = directory.lastModified(),
                    state = CachedModState.Cached,
                )
            }.getOrNull()
        }
    }

    fun clear() {
        cacheRoot.deleteRecursively()
        namePreferences?.edit()?.clear()?.apply()
    }

    fun deleteCached(cacheKey: String): CachedModDeletionResult {
        if (!cacheKey.matches(CACHE_KEY_REGEX)) return CachedModDeletionResult.Rejected("Mod 缓存标识无效。")
        if (cacheRoot.exists() && isSymbolicLink(cacheRoot)) return CachedModDeletionResult.Rejected("私有缓存目录不可安全访问。")
        val target = File(cacheRoot, cacheKey)
        if (!target.exists()) return CachedModDeletionResult.NotFound
        if (!target.isDirectory || isSymbolicLink(target)) return CachedModDeletionResult.Rejected("Mod 缓存目录不可安全访问。")
        if (containsSymbolicLink(target)) return CachedModDeletionResult.Rejected("Mod 缓存包含不安全链接。")
        return try {
            if (!deleteRecursivelyWithoutLinks(target) || target.exists()) {
                CachedModDeletionResult.Failed("无法完全删除私有缓存目录。")
            } else {
                namePreferences?.edit()?.remove(cacheKey)?.apply()
                CachedModDeletionResult.Deleted
            }
        } catch (_: SecurityException) {
            CachedModDeletionResult.Failed("没有删除私有缓存的权限。")
        } catch (error: Exception) {
            CachedModDeletionResult.Failed(error.message ?: "无法删除私有缓存目录。")
        }
    }

    fun recoverInterruptedImports() {
        cacheRoot.listFiles()?.filter { it.name.startsWith('.') && it.name.endsWith(".partial") }?.forEach(File::deleteRecursively)
    }

    fun importDirectory(sourceRoot: File, source: CacheSource, displayName: String = sourceRoot.name): CachedMod =
        importDirectoriesWithNames(listOf(sourceRoot to displayName), source).single()

    fun importDirectories(sourceRoots: List<File>, source: CacheSource): List<CachedMod> =
        importDirectoriesWithNames(sourceRoots.map { it to it.name }, source)

    fun importDirectoriesWithNames(sourceRoots: List<Pair<File, String>>, source: CacheSource): List<CachedMod> {
        if (sourceRoots.isEmpty()) return emptyList()
        val validated = sourceRoots.map { (root, displayName) -> root to validateDirectory(root, displayName) }.distinctBy { it.second.digest }
        if (!cacheRoot.mkdirs() && !cacheRoot.isDirectory) throw ImportValidationException("无法创建私有缓存目录")
        val pending = mutableListOf<PendingImport>()
        val committed = mutableListOf<File>()
        try {
            validated.forEach { (sourceRoot, expected) ->
                val destination = File(cacheRoot, expected.digest)
                if (destination.isDirectory && !isSymbolicLink(destination)) {
                    pending += PendingImport(expected, null)
                    return@forEach
                }
                val staging = File(cacheRoot, ".${UUID.randomUUID()}.partial")
                pending += PendingImport(expected, staging)
                budget.requireSpace(cacheRoot, expected.sizeBytes, "缓存导入")
                copyDirectory(sourceRoot, staging, 0)
                val copied = validateDirectory(staging, expected.name)
                if (copied.digest != expected.digest || copied.sizeBytes != expected.sizeBytes) throw ImportValidationException("导入内容在复制期间发生变化")
            }
            return pending.map { item ->
                val destination = File(cacheRoot, item.validated.digest)
                if (item.staging != null && !destination.exists()) {
                    if (!item.staging.renameTo(destination)) throw ImportValidationException("无法提交私有缓存")
                    committed += destination
                } else item.staging?.deleteRecursively()
                saveDisplayName(item.validated.digest, item.validated.name)
                CachedMod(item.validated.digest, item.validated.digest, item.validated.name, source, item.validated.sizeBytes, System.currentTimeMillis(), CachedModState.Cached)
            }
        } catch (error: Exception) {
            pending.mapNotNull { it.staging }.forEach(File::deleteRecursively)
            committed.forEach(File::deleteRecursively)
            throw error
        }
    }

    private data class PendingImport(val validated: AndroidValidatedMod, val staging: File?)

    fun validateDirectory(sourceRoot: File): AndroidValidatedMod = validateDirectory(sourceRoot, sourceRoot.name)

    private fun validateDirectory(sourceRoot: File, displayName: String): AndroidValidatedMod {
        if (!sourceRoot.isDirectory || isSymbolicLink(sourceRoot)) throw ImportValidationException("Mod 根目录不可读")
        return validate(sourceRoot, displayName)
    }

    private fun copyDirectory(source: File, target: File, depth: Int) {
        if (depth > com.sultansgame.modmanager.model.MAXIMUM_MOD_PATH_DEPTH) throw ImportValidationException("目录深度超出限制")
        if (!target.mkdirs() && !target.isDirectory) throw ImportValidationException("无法创建私有导入目录")
        source.listFiles()?.sortedBy { it.name }?.forEach { entry ->
            if (isSymbolicLink(entry) || ModPathPolicy.isUnsafeComponent(entry.name)) throw ImportValidationException("包含不安全路径")
            val destination = File(target, entry.name)
            when {
                entry.isDirectory -> copyDirectory(entry, destination, depth + 1)
                entry.isFile -> copyFile(entry, destination)
                else -> throw ImportValidationException("包含非普通文件")
            }
        } ?: throw ImportValidationException("无法读取导入目录")
    }

    private fun copyFile(source: File, destination: File) {
        val size = source.length()
        if (!ModPathPolicy.isSupportedSize(size, source.name)) throw ImportValidationException("文件大小超出限制")
        source.inputStream().use { input -> destination.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var written = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                budget.checkChunk(cacheRoot, written, count.toLong(), "缓存复制")
                output.write(buffer, 0, count)
                written = Math.addExact(written, count.toLong())
            }
            if (written != size) throw ImportValidationException("导入文件在复制期间发生变化")
        } }
    }

    private fun validate(root: File, displayName: String): AndroidValidatedMod {
        val manifestCount = root.listFiles()?.count { it.isFile && it.name.equals("info.json", ignoreCase = true) }
            ?: throw ImportValidationException("无法读取导入目录")
        if (manifestCount != 1) throw ImportValidationException("根目录必须包含唯一的 Info.json")
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
                        total = try { Math.addExact(total, size) } catch (_: ArithmeticException) { throw ImportValidationException("Mod 总大小超出可表示范围") }
                        entries += "$relative\t$size\t${sha256(entry)}"
                    }
                    else -> throw ImportValidationException("包含非普通文件")
                }
            } ?: throw ImportValidationException("无法读取导入目录")
        }
        scan(root, "", 0)
        val digest = MessageDigest.getInstance("SHA-256").digest(entries.sorted().joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
        return AndroidValidatedMod(ModDisplayNamePolicy.normalize(displayName) ?: ModDisplayNamePolicy.fallback(digest), digest, total)
    }

    private fun migrateLegacyDisplayName(directory: File, cacheKey: String): String {
        val name = runCatching {
            val manifest = directory.listFiles()?.singleOrNull { it.isFile && it.name.equals("info.json", ignoreCase = true) }
                ?: return@runCatching null
            manifestValidator.parse(manifest.readBytes()).name
        }.getOrNull()?.let(ModDisplayNamePolicy::normalize) ?: ModDisplayNamePolicy.fallback(cacheKey)
        saveDisplayName(cacheKey, name)
        return name
    }

    private fun saveDisplayName(cacheKey: String, displayName: String) { namePreferences?.edit()?.putString(cacheKey, displayName)?.apply() }

    private fun containsSymbolicLink(directory: File): Boolean {
        if (isSymbolicLink(directory)) return true
        val children = directory.listFiles() ?: return true
        return children.any { child -> isSymbolicLink(child) || (child.isDirectory && containsSymbolicLink(child)) }
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
        const val NAME_PREFERENCES = "mod-cache-names"
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

    private fun isSymbolicLink(file: File): Boolean = runCatching { OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode) }.getOrDefault(true)
}
