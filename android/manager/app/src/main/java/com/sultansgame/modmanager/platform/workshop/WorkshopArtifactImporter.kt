package com.sultansgame.modmanager.platform.workshop

import android.content.Context
import com.sultansgame.modmanager.model.CacheSource
import com.sultansgame.modmanager.model.CachedMod
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.platform.saf.ZipModImporter
import com.sultansgame.modmanager.platform.storage.AndroidPrivateModCache
import com.sultansgame.modmanager.storage.ImportValidationException
import java.io.File
import java.util.UUID

class WorkshopArtifactImporter(
    private val context: Context,
    private val cache: AndroidPrivateModCache,
    private val zipImporter: ZipModImporter,
) {
    fun verifyPendingArtifact(task: DownloadTask) {
        val staging = stagingDirectory(task.id)
            ?.takeIf(File::isDirectory)
            ?: throw ImportValidationException("下载内容已不可用")
        WorkshopStagingArtifact.verify(staging, task)
    }

    fun importConfirmed(task: DownloadTask): CachedMod {
        val staging = stagingDirectory(task.id)
            ?.takeIf(File::isDirectory)
            ?: throw ImportValidationException("下载内容已不可用")
        WorkshopStagingArtifact.verify(staging, task)
        val payloads = WorkshopStagingArtifact.payloadRoots(staging)
        if (payloads.size == 1 && payloads.single().isFile && isZip(payloads.single())) {
            return zipImporter.importDownloadedZip(payloads.single()).copy(
                source = CacheSource.Workshop,
                publishedFileId = task.publishedFileId,
            )
        }
        val root = when {
            hasManifest(staging) -> staging
            payloads.size == 1 && payloads.single().isDirectory && hasManifest(payloads.single()) -> payloads.single()
            else -> throw ImportValidationException("下载结果不是包含唯一 Info.json 的 ZIP 或 Mod 目录")
        }
        return cache.importDirectory(root, CacheSource.Workshop).copy(publishedFileId = task.publishedFileId)
    }

    fun discard(task: DownloadTask) {
        stagingDirectory(task.id)?.deleteRecursively()
    }

    private fun stagingDirectory(taskId: String): File? = runCatching {
        UUID.fromString(taskId)
        val root = File(context.filesDir, "workshop-staging").canonicalFile
        val directory = File(root, taskId).canonicalFile
        directory.takeIf { it.path.startsWith(root.path + File.separator) }
    }.getOrNull()

    private fun isZip(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            input.read() == 'P'.code && input.read() == 'K'.code
        }
    }.getOrDefault(false)

    private fun hasManifest(directory: File): Boolean = directory.listFiles()
        ?.count { it.isFile && it.name.equals("info.json", ignoreCase = true) } == 1
}
