package com.sultansgame.modmanager.platform.export

import com.sultansgame.modmanager.platform.storage.AndroidModExportFile
import com.sultansgame.modmanager.platform.storage.AndroidModExportSnapshot
import com.sultansgame.modmanager.platform.storage.AndroidPrivateModCache
import java.io.File
import java.util.Locale
import java.util.UUID
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod

class ModZipExporter(
    private val cache: AndroidPrivateModCache,
    private val exportRoot: File,
) {
    data class Progress(
        val completedFiles: Int,
        val totalFiles: Int,
        val writtenBytes: Long,
        val totalBytes: Long,
    )

    data class Artifact(
        val id: String,
        val file: File,
        val fileName: String,
    )

    fun cleanupInterrupted() {
        exportRoot.listFiles()?.filter { it.name.endsWith(".partial") }?.forEach(File::delete)
    }

    fun export(
        cacheKeys: List<String>,
        fileName: String,
        password: CharArray,
        onProgress: (Progress) -> Unit = {},
    ): Artifact {
        val snapshots = cache.exportSnapshots(cacheKeys)
        val outputName = sanitizeFileName(fileName)
        if (!exportRoot.mkdirs() && !exportRoot.isDirectory) {
            throw IllegalStateException("无法创建 Mod 导出目录。")
        }
        val roots = uniqueRootNames(snapshots)
        val totalFiles = snapshots.sumOf { it.files.size }
        val totalBytes = snapshots.sumOf(AndroidModExportSnapshot::sizeBytes)
        val id = UUID.randomUUID().toString()
        val partial = File(exportRoot, "$id.partial")
        val target = File(exportRoot, "$id.zip")
        var completedFiles = 0
        var writtenBytes = 0L
        try {
            ZipOutputStream(partial.outputStream(), password.takeIf { it.isNotEmpty() }).use { output ->
                snapshots.forEachIndexed { snapshotIndex, snapshot ->
                    snapshot.files.forEach { entry ->
                        cache.verifyExportFile(snapshot, entry)
                        val parameters = ZipParameters().apply {
                            fileNameInZip = "${roots[snapshotIndex]}/${entry.relativePath}"
                            compressionMethod = CompressionMethod.DEFLATE
                            compressionLevel = CompressionLevel.NORMAL
                            if (password.isNotEmpty()) {
                                isEncryptFiles = true
                                encryptionMethod = EncryptionMethod.AES
                                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                            }
                        }
                        output.putNextEntry(parameters)
                        entry.file.inputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                writtenBytes = Math.addExact(writtenBytes, count.toLong())
                                onProgress(Progress(completedFiles, totalFiles, writtenBytes, totalBytes))
                            }
                        }
                        output.closeEntry()
                        cache.verifyExportFile(snapshot, entry)
                        completedFiles++
                        onProgress(Progress(completedFiles, totalFiles, writtenBytes, totalBytes))
                    }
                }
            }
            if (!partial.renameTo(target)) throw IllegalStateException("无法提交 Mod 导出文件。")
            return Artifact(id, target, outputName)
        } catch (error: Throwable) {
            partial.delete()
            target.delete()
            throw error
        }
    }

    private fun uniqueRootNames(snapshots: List<AndroidModExportSnapshot>): List<String> {
        val used = mutableSetOf<String>()
        return snapshots.map { snapshot ->
            val base = sanitizeRootName(snapshot.displayName, snapshot.cacheKey)
            var candidate = base
            if (!used.add(candidate.lowercase(Locale.ROOT))) {
                candidate = "$base-${snapshot.cacheKey.take(8)}"
                if (!used.add(candidate.lowercase(Locale.ROOT))) {
                    candidate = "$base-${snapshot.cacheKey}"
                    used.add(candidate.lowercase(Locale.ROOT))
                }
            }
            candidate
        }
    }

    private fun sanitizeRootName(value: String, cacheKey: String): String {
        val cleaned = value.trim().filter { !it.isISOControl() && it != '/' && it != '\\' && it != '\u0000' }
        return cleaned.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "mod-${cacheKey.take(8)}"
    }

    private fun sanitizeFileName(value: String): String {
        val cleaned = value.trim().filter { !it.isISOControl() && it != '/' && it != '\\' && it != '\u0000' }
            .trimEnd('.', ' ')
        val base = cleaned.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "sultans-game-mods"
        return if (base.endsWith(".zip", ignoreCase = true)) base else "$base.zip"
    }
}
