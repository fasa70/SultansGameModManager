package com.sultansgame.modmanager.platform.saf

import android.content.Context
import android.net.Uri
import com.sultansgame.modmanager.model.CacheSource
import com.sultansgame.modmanager.model.CachedMod
import com.sultansgame.modmanager.model.MAXIMUM_MOD_PATH_DEPTH
import com.sultansgame.modmanager.platform.storage.AndroidPrivateModCache
import com.sultansgame.modmanager.storage.ImportValidationException
import com.sultansgame.modmanager.storage.MAXIMUM_MOD_ENTRY_COUNT
import com.sultansgame.modmanager.storage.MAXIMUM_MOD_TOTAL_SIZE_BYTES
import com.sultansgame.modmanager.storage.ModPathPolicy
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

class ZipModImporter(
    private val context: Context,
    private val cache: AndroidPrivateModCache,
) {
    fun importZip(uri: Uri): List<CachedMod> = context.contentResolver.openInputStream(uri)
        ?.use { source -> importZipStream(source, allowMultipleRoots = true) }
        ?: throw ImportValidationException("无法读取所选 ZIP")

    fun importDownloadedZip(file: File): CachedMod {
        if (!file.isFile) throw ImportValidationException("下载的 ZIP 不可读")
        return FileInputStream(file).use { source ->
            importZipStream(source, allowMultipleRoots = false).single()
        }
    }

    private fun importZipStream(source: InputStream, allowMultipleRoots: Boolean): List<CachedMod> {
        val stagingRoot = File(context.filesDir, "mod-cache")
        if (!stagingRoot.mkdirs() && !stagingRoot.isDirectory) throw ImportValidationException("无法创建私有导入目录")
        val staging = File(stagingRoot, ".${UUID.randomUUID()}.partial")
        try {
            extractZip(source, staging)
            val roots = resolveModRoots(staging)
            if (!allowMultipleRoots && roots.size != 1) {
                throw ImportValidationException("下载的 ZIP 必须只包含一个 Mod 根目录")
            }
            roots.forEach(cache::validateDirectory)
            return roots.map { root ->
                cache.importDirectory(root, CacheSource.SafArchive)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extractZip(source: InputStream, root: File) {
        if (!root.mkdirs() && !root.isDirectory) throw ImportValidationException("无法创建私有导入目录")
        val paths = mutableSetOf<String>()
        val caseFoldedPaths = mutableSetOf<String>()
        var entries = 0
        var totalSize = 0L
        ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val normalized = normalizeEntry(entry.name)
                if (++entries > MAXIMUM_MOD_ENTRY_COUNT) throw ImportValidationException("文件或目录数量超出限制")
                if (!paths.add(normalized) || !caseFoldedPaths.add(normalized.lowercase())) {
                    throw ImportValidationException("ZIP 包含重复或大小写冲突路径")
                }
                val destination = File(root, normalized)
                if (!destination.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                    throw ImportValidationException("ZIP 包含不安全路径")
                }
                if (entry.isDirectory) {
                    if (!destination.mkdirs() && !destination.isDirectory) throw ImportValidationException("无法创建 ZIP 目录")
                } else {
                    destination.parentFile?.mkdirs()
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileSize = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            fileSize += count
                            if (!ModPathPolicy.isSupportedSize(fileSize, normalized)) throw ImportValidationException("ZIP 文件大小超出限制")
                            totalSize = Math.addExact(totalSize, count.toLong())
                            if (totalSize > MAXIMUM_MOD_TOTAL_SIZE_BYTES) throw ImportValidationException("ZIP 总大小超出限制")
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun normalizeEntry(name: String): String {
        if (name.isEmpty() || name.startsWith('/') || name.startsWith('\\')) throw ImportValidationException("ZIP 包含不安全路径")
        val components = name.trimEnd('/').split('/')
        if (components.isEmpty() || components.size - 1 > MAXIMUM_MOD_PATH_DEPTH || components.any(ModPathPolicy::isUnsafeComponent)) {
            throw ImportValidationException("ZIP 包含不安全路径")
        }
        return components.joinToString("/")
    }

    private fun resolveModRoots(staging: File): List<File> {
        if (hasManifest(staging)) return listOf(staging)
        val entries = staging.listFiles() ?: throw ImportValidationException("无法读取 ZIP 内容")
        val roots = entries.filter(File::isDirectory).sortedBy(File::getName)
        if (roots.isNotEmpty() && roots.size == entries.size && roots.all(::hasManifest)) return roots
        throw ImportValidationException("ZIP 必须包含 Info.json，或仅包含多个各自带有 Info.json 的 Mod 根目录")
    }

    private fun hasManifest(directory: File): Boolean = directory.listFiles()
        ?.count { it.isFile && it.name.equals("info.json", ignoreCase = true) } == 1
}
