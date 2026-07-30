package com.sultansgame.modmanager.platform.patch

import com.sultansgame.modmanager.apk.ReadOnlyApkInspector
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class ApksExporter(
    private val transactions: PatchTransactionStore,
) {
    fun export(transactionId: String, output: OutputStream) {
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
        }
        val digests = artifacts.map { artifact ->
            ReadOnlyApkInspector().sha256 { artifact.inputStream() }
        }
        require(digests == transaction.artifactDigests) { "签名 APK 暂存文件摘要不匹配。" }

        ZipOutputStream(output).use { zip ->
            artifacts.forEach { artifact ->
                zip.putNextEntry(ZipEntry(artifact.name))
                artifact.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
