package com.sultansgame.modmanager.platform.workshop

import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.storage.ImportValidationException
import java.io.File
import java.security.MessageDigest

/** The exact staging payload that may be handed to the Mod importer. */
internal object WorkshopStagingArtifact {
    data class Summary(
        val sha256: String,
        val fileCount: Int,
    )

    fun payloadRoots(staging: File): List<File> = staging.listFiles()
        ?.filterNot { isTransientRoot(it) }
        ?.sortedBy { it.name }
        .orEmpty()

    fun summarize(staging: File): Summary {
        require(staging.isDirectory) { "下载内容已不可用" }
        val payload = payloadRoots(staging)
        if (payload.isEmpty()) throw ImportValidationException("下载内容不包含可导入文件")

        val digest = MessageDigest.getInstance("SHA-256")
        var fileCount = 0
        payload.flatMap { root ->
            if (root.isFile) listOf(root) else root.walkTopDown().filter(File::isFile).toList()
        }.sortedBy { it.relativeTo(staging).invariantSeparatorsPath }
            .forEach { file ->
                val path = file.relativeTo(staging).invariantSeparatorsPath
                if (isTransientPath(path)) return@forEach
                digest.update(path.toByteArray(Charsets.UTF_8))
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                fileCount++
            }
        if (fileCount == 0) throw ImportValidationException("下载内容不包含可导入文件")
        return Summary(
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            fileCount = fileCount,
        )
    }

    fun verify(staging: File, task: DownloadTask) {
        val expectedDigest = task.rawArtifactDigestSha256
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE)) }
            ?: throw ImportValidationException("下载工件摘要不可用")
        val summary = summarize(staging)
        if (!summary.sha256.equals(expectedDigest, ignoreCase = true) || summary.fileCount != task.completedFileCount) {
            throw ImportValidationException("下载内容已变更，必须重新下载")
        }
    }

    private fun isTransientRoot(file: File): Boolean =
        file.name == "metadata.json" ||
            file.name == "download.log" ||
            file.name == ".chunks" ||
            file.name.endsWith(".part") ||
            file.name.endsWith(".tmp")

    private fun isTransientPath(path: String): Boolean =
        path == "download.log" ||
            path == "metadata.json" ||
            path.startsWith(".chunks/") ||
            path.endsWith(".part") ||
            path.endsWith(".tmp")
}
