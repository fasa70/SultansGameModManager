package com.sultansgame.modmanager.platform.patch

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal sealed interface ApksExportProgress {
    data object Validating : ApksExportProgress

    data class Writing(
        val artifactName: String,
        val artifactIndex: Int,
        val artifactCount: Int,
        val writtenBytes: Long,
        val totalBytes: Long,
    ) : ApksExportProgress
}

internal class ApksExporter(
    private val transactions: PatchTransactionStore,
) {
    fun export(
        transactionId: String,
        output: OutputStream,
        onProgress: (ApksExportProgress) -> Unit = {},
    ) {
        onProgress(ApksExportProgress.Validating)
        val transaction = requireNotNull(transactions.read(transactionId)) {
            "找不到待导出的修补事务。"
        }
        val artifactNames = transaction.signedArtifactNames
        require(artifactNames.isNotEmpty()) { "修补事务缺少签名 APK 集合。" }
        require(artifactNames.size == transaction.artifactDigests.size) { "修补事务的 APK 摘要不完整。" }
        require(artifactNames.distinct().size == artifactNames.size) { "修补事务包含重复 APK 文件名。" }

        val signedDirectory = File(transactions.root(transactionId), "signed")
        val artifacts = artifactNames.map { name ->
            require(name == File(name).name && name.endsWith(".apk")) { "签名 APK 文件名无效。" }
            File(signedDirectory, name).also { artifact ->
                require(artifact.isFile) { "签名 APK 暂存文件不完整。" }
            }
        }.mapIndexed { index, artifact ->
            scanArtifact(artifact, transaction.artifactDigests[index])
        }
        val totalBytes = artifacts.fold(0L) { total, artifact -> Math.addExact(total, artifact.size) }

        ZipOutputStream(output).use { zip ->
            var writtenBytes = 0L
            artifacts.forEachIndexed { index, artifact ->
                val entry = ZipEntry(artifact.file.name).apply {
                    method = ZipEntry.STORED
                    size = artifact.size
                    compressedSize = artifact.size
                    crc = artifact.crc
                }
                zip.putNextEntry(entry)
                artifact.file.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val crc = CRC32()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var writtenForArtifact = 0L
                    var reportedForArtifact = 0L
                    while (writtenForArtifact < artifact.size) {
                        val remaining = artifact.size - writtenForArtifact
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(count != -1) { "签名 APK 暂存文件在导出时发生变化。" }
                        digest.update(buffer, 0, count)
                        crc.update(buffer, 0, count)
                        zip.write(buffer, 0, count)
                        writtenForArtifact += count
                        writtenBytes += count
                        if (writtenForArtifact - reportedForArtifact >= PROGRESS_INTERVAL_BYTES) {
                            onProgress(
                                ApksExportProgress.Writing(
                                    artifact.file.name,
                                    index,
                                    artifacts.size,
                                    writtenBytes,
                                    totalBytes,
                                ),
                            )
                            reportedForArtifact = writtenForArtifact
                        }
                    }
                    check(input.read() == -1) { "签名 APK 暂存文件在导出时发生变化。" }
                    check(digest.digest().toHex() == artifact.digest && crc.value == artifact.crc) {
                        "签名 APK 暂存文件在导出时发生变化。"
                    }
                }
                zip.closeEntry()
                onProgress(
                    ApksExportProgress.Writing(
                        artifact.file.name,
                        index,
                        artifacts.size,
                        writtenBytes,
                        totalBytes,
                    ),
                )
            }
        }
    }

    private fun scanArtifact(artifact: File, expectedDigest: String): ExportArtifact {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        artifact.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
                crc.update(buffer, 0, count)
                size = Math.addExact(size, count.toLong())
            }
        }
        val actualDigest = digest.digest().toHex()
        require(actualDigest == expectedDigest) { "签名 APK 暂存文件摘要不匹配。" }
        return ExportArtifact(artifact, size, crc.value, actualDigest)
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private data class ExportArtifact(
        val file: File,
        val size: Long,
        val crc: Long,
        val digest: String,
    )

    private companion object {
        const val PROGRESS_INTERVAL_BYTES = 256L * 1024L
    }
}
