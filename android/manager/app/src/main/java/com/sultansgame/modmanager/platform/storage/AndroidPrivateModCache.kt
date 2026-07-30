package com.sultansgame.modmanager.platform.storage

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

    fun recoverInterruptedImports() {
        cacheRoot.listFiles()
            ?.filter { it.name.startsWith('.') && it.name.endsWith(".partial") }
            ?.forEach(File::deleteRecursively)
    }
    fun importDirectory(sourceRoot: File, source: CacheSource): CachedMod {
        val validated = validateDirectory(sourceRoot)
        if (!cacheRoot.mkdirs() && !cacheRoot.isDirectory) throw ImportValidationException("无法创建私有缓存目录")
        val staging = File(cacheRoot, ".${UUID.randomUUID()}.partial")
        try {
            copyDirectory(sourceRoot, staging, 0)
            val destination = File(cacheRoot, validated.digest)
            if (!destination.exists() && !staging.renameTo(destination)) throw ImportValidationException("无法提交私有缓存")
            if (destination.exists() && staging.exists()) staging.deleteRecursively()
            return CachedMod(
                cacheKey = validated.digest,
                contentDigestSha256 = validated.digest,
                displayName = validated.name,
                source = source,
                sizeBytes = validated.sizeBytes,
                importedAtEpochMillis = System.currentTimeMillis(),
                state = CachedModState.Cached,
            )
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

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
        file.canonicalFile != file.absoluteFile
    }.getOrDefault(true)
}
