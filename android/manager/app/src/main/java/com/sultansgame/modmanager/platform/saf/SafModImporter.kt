package com.sultansgame.modmanager.platform.saf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sultansgame.modmanager.model.CacheSource
import com.sultansgame.modmanager.model.CachedMod
import com.sultansgame.modmanager.model.MAXIMUM_MOD_PATH_DEPTH
import com.sultansgame.modmanager.platform.storage.AndroidPrivateModCache
import com.sultansgame.modmanager.storage.ImportValidationException
import com.sultansgame.modmanager.storage.MAXIMUM_MOD_ENTRY_COUNT
import com.sultansgame.modmanager.storage.ModDisplayNamePolicy
import com.sultansgame.modmanager.storage.ModPathPolicy
import com.sultansgame.modmanager.storage.StorageBudget
import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

sealed class ZipImportException(message: String) : IllegalArgumentException(message) {
    class PasswordRequired : ZipImportException("ZIP 需要密码")
    class InvalidPasswordOrEncryptedData : ZipImportException("ZIP 密码错误或加密内容已损坏")
    class InvalidArchive : ZipImportException("ZIP 格式无效或内容已损坏")
}

data class ZipArchiveInspection(val passwordRequired: Boolean)

class ZipModImporter(
    private val context: Context,
    private val cache: AndroidPrivateModCache,
    private val budget: StorageBudget = StorageBudget.UNBOUNDED,
) {
    fun inspect(file: File): ZipArchiveInspection {
        validateArchiveFile(file)
        return try {
            ZipFile(file).use { zip -> ZipArchiveInspection(zip.isEncrypted) }
        } catch (_: Exception) {
            throw ZipImportException.InvalidArchive()
        }
    }

    fun importZip(uri: Uri): List<CachedMod> {
        val temporary = File(context.cacheDir, ".zip-import-${UUID.randomUUID()}.zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    copyBounded(input, output)
                    output.fd.sync()
                }
            } ?: throw ImportValidationException("无法读取所选 ZIP")
            return importZip(temporary, archiveDisplayName = displayNameFor(uri))
        } finally {
            temporary.delete()
        }
    }

    fun importZip(
        file: File,
        password: CharArray? = null,
        archiveDisplayName: String? = null,
    ): List<CachedMod> {
        validateArchiveFile(file)
        return importZipFile(file, allowMultipleRoots = true, password = password, archiveDisplayName = archiveDisplayName)
    }

    fun importDownloadedZip(file: File, password: CharArray? = null, displayName: String? = null): CachedMod {
        validateArchiveFile(file)
        return importZipFile(file, allowMultipleRoots = false, password = password, archiveDisplayName = displayName).single()
    }

    private fun importZipFile(
        file: File,
        allowMultipleRoots: Boolean,
        password: CharArray?,
        archiveDisplayName: String? = null,
    ): List<CachedMod> {
        val stagingRoot = File(context.filesDir, "mod-cache")
        if (!stagingRoot.mkdirs() && !stagingRoot.isDirectory) throw ImportValidationException("无法创建私有导入目录")
        val staging = File(stagingRoot, ".${UUID.randomUUID()}.partial")
        try {
            extractZip(file, staging, password)
            val roots = resolveModRoots(staging)
            if (!allowMultipleRoots && roots.size != 1) {
                throw ImportValidationException("下载的 ZIP 必须只包含一个 Mod 根目录")
            }
            val names = roots.map { root ->
                val sourceName = if (root == staging) archiveDisplayName ?: file.nameWithoutExtension else root.name
                ModDisplayNamePolicy.normalize(sourceName) ?: file.nameWithoutExtension
            }
            return cache.importDirectoriesWithNames(roots.zip(names), CacheSource.SafArchive)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extractZip(file: File, root: File, password: CharArray?) {
        if (!root.mkdirs() && !root.isDirectory) throw ImportValidationException("无法创建私有导入目录")
        val paths = mutableSetOf<String>()
        val caseFoldedPaths = mutableSetOf<String>()
        var entries = 0
        var totalSize = 0L
        val zip = try {
            ZipFile(file).apply {
                if (isEncrypted) {
                    if (password == null) throw ZipImportException.PasswordRequired()
                    setPassword(password)
                }
            }
        } catch (error: ZipImportException) {
            throw error
        } catch (_: Exception) {
            throw ZipImportException.InvalidArchive()
        }
        zip.use { archive ->
            val headers = try { archive.fileHeaders } catch (_: Exception) { throw ZipImportException.InvalidArchive() }
            headers.forEach { entry ->
                val normalized = normalizeEntry(entry.fileName)
                if (++entries > MAXIMUM_MOD_ENTRY_COUNT) throw ImportValidationException("文件或目录数量超出限制")
                if (!paths.add(normalized) || !caseFoldedPaths.add(normalized.lowercase(Locale.ROOT))) {
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
                    try {
                        archive.getInputStream(entry).use { input ->
                            FileOutputStream(destination).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var fileSize = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    budget.checkChunk(root, fileSize, count.toLong(), "解压 ZIP")
                                    fileSize = Math.addExact(fileSize, count.toLong())
                                    totalSize = try { Math.addExact(totalSize, count.toLong()) } catch (_: ArithmeticException) {
                                        throw ImportValidationException("ZIP 总大小超出可表示范围")
                                    }
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                            }
                        }
                    } catch (error: ImportValidationException) {
                        throw error
                    } catch (_: Exception) {
                        throw if (archive.isEncrypted) ZipImportException.InvalidPasswordOrEncryptedData() else ZipImportException.InvalidArchive()
                    }
                }
            }
        }
    }

    private fun validateArchiveFile(file: File) {
        if (!file.isFile) throw ImportValidationException("ZIP 文件不可读")
    }

    private fun copyBounded(input: java.io.InputStream, output: FileOutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            budget.checkChunk(context.cacheDir, total, count.toLong(), "接收 ZIP")
            total = Math.addExact(total, count.toLong())
            output.write(buffer, 0, count)
        }
    }

    private fun displayNameFor(uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        cursor.takeIf { column >= 0 && it.moveToFirst() }?.getString(column)
    }?.let { name ->
        name.removeSuffix(".zip").removeSuffix(".ZIP")
    } ?: uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".zip")

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
