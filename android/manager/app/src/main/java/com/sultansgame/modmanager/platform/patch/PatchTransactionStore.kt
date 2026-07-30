package com.sultansgame.modmanager.platform.patch

import android.content.Context
import com.sultansgame.modmanager.model.PatchMode
import com.sultansgame.modmanager.model.PatchStage
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

internal data class PatchTransaction(
    val id: String,
    val stage: PatchStage,
    val sessionId: Int?,
    val artifactDigests: List<String>,
    val mode: PatchMode? = null,
    val profileId: String? = null,
    val expectedCertificateSha256: String? = null,
    val expectedVersionCode: Long? = null,
    val expectedSplitNames: List<String> = emptyList(),
    val signedArtifactNames: List<String> = emptyList(),
    val failure: String? = null,
)

internal data class PatchArtifactCleanupCandidate(
    val transactionId: String,
    val sizeBytes: Long,
    val stage: PatchStage,
)

internal sealed interface PatchArtifactCleanupResult {
    data object Deleted : PatchArtifactCleanupResult
    data object NotFound : PatchArtifactCleanupResult
    data class Rejected(val reason: String) : PatchArtifactCleanupResult
    data class Failed(val reason: String) : PatchArtifactCleanupResult
}

internal class PatchTransactionStore {
    private val root: File

    constructor(context: Context) {
        root = File(context.filesDir, "patch-staging")
    }

    internal constructor(root: File) {
        this.root = root
    }

    fun root(transactionId: String): File = File(root, transactionId)

    fun latestPreparedForRecovery(): PatchTransaction? = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory -> read(directory.name)?.let { transaction -> transaction to directory } }
        .filter { (transaction, _) -> transaction.isPreparedForRecovery() }
        .maxByOrNull { (_, directory) -> File(directory, "transaction.properties").lastModified() }
        ?.first

    fun latestResumable(): PatchTransaction? = latestPreparedForRecovery()

    fun latestCleanupCandidate(): PatchArtifactCleanupCandidate? = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory -> read(directory.name)?.let { transaction -> transaction to directory } }
        .filter { (transaction, directory) -> transaction.isCleanupCandidate(directory) }
        .maxByOrNull { (_, directory) -> File(directory, "transaction.properties").lastModified() }
        ?.let { (transaction, directory) ->
            PatchArtifactCleanupCandidate(
                transactionId = transaction.id,
                sizeBytes = directorySize(directory),
                stage = transaction.stage,
            )
        }

    fun deletePreparedArtifacts(transactionId: String): PatchArtifactCleanupResult {
        if (!isSafeTransactionId(transactionId)) {
            return PatchArtifactCleanupResult.Rejected("修补事务标识无效。")
        }
        val directory = root(transactionId)
        if (!directory.exists()) return PatchArtifactCleanupResult.NotFound
        val transaction = read(transactionId)
            ?: return PatchArtifactCleanupResult.Rejected("修补事务记录无效，无法安全删除。")
        if (!transaction.isCleanupCandidate(directory)) {
            return PatchArtifactCleanupResult.Rejected("当前修补事务仍在安装或校验中，暂不能删除。")
        }
        return runCatching {
            if (directory.deleteRecursively()) PatchArtifactCleanupResult.Deleted
            else PatchArtifactCleanupResult.Failed("部分私有修补文件未能删除。")
        }.getOrElse { error ->
            PatchArtifactCleanupResult.Failed(error.message ?: "无法删除私有修补文件。")
        }
    }

    fun latestAwaitingGameUninstall(): PatchTransaction? = latestResumable()
        ?.takeIf { it.stage == PatchStage.AwaitingGameUninstall }

    fun write(transaction: PatchTransaction) {
        val directory = File(root, transaction.id).apply { mkdirs() }
        val temporary = File(directory, "transaction.properties.tmp")
        val target = File(directory, "transaction.properties")
        Properties().apply {
            setProperty("stage", transaction.stage.name)
            transaction.sessionId?.let { setProperty("sessionId", it.toString()) }
            setProperty("digests", transaction.artifactDigests.joinToString(","))
            transaction.mode?.let { setProperty("mode", it.name) }
            transaction.profileId?.let { setProperty("profileId", it) }
            transaction.expectedCertificateSha256?.let { setProperty("certificate", it) }
            transaction.expectedVersionCode?.let { setProperty("versionCode", it.toString()) }
            setProperty("splitNames", transaction.expectedSplitNames.joinToString(","))
            setProperty("signedArtifacts", transaction.signedArtifactNames.joinToString(","))
            transaction.failure?.let { setProperty("failure", it) }
            FileOutputStream(temporary).use { output ->
                store(output, null)
                output.fd.sync()
            }
        }
        check(temporary.renameTo(target)) { "无法提交补丁事务日志" }
    }

    fun read(id: String): PatchTransaction? {
        val file = File(root, "$id/transaction.properties")
        if (!file.isFile) return null
        return runCatching {
            Properties().run {
                file.inputStream().use(::load)
                PatchTransaction(
                    id = id,
                    stage = PatchStage.valueOf(getProperty("stage")),
                    sessionId = getProperty("sessionId")?.toIntOrNull(),
                    artifactDigests = getProperty("digests").orEmpty().split(',').filter(String::isNotBlank),
                    mode = getProperty("mode")?.let(PatchMode::valueOf),
                    profileId = getProperty("profileId"),
                    expectedCertificateSha256 = getProperty("certificate"),
                    expectedVersionCode = getProperty("versionCode")?.toLongOrNull(),
                    expectedSplitNames = getProperty("splitNames").orEmpty().split(',').filter(String::isNotBlank),
                    signedArtifactNames = getProperty("signedArtifacts").orEmpty().split(',').filter(String::isNotBlank),
                    failure = getProperty("failure"),
                )
            }
        }.getOrNull()
    }

    private fun PatchTransaction.isCleanupCandidate(directory: File): Boolean =
        stage in CLEANUP_STAGES &&
            signedArtifactNames.isNotEmpty() &&
            signedArtifactNames.distinct().size == signedArtifactNames.size &&
            signedArtifactNames.all { it == File(it).name } &&
            signedArtifactNames.all { name -> File(directory, "signed/$name").isFile }

    private fun directorySize(directory: File): Long = directory.walkTopDown()
        .filter(File::isFile)
        .fold(0L) { total, file ->
            if (Long.MAX_VALUE - total < file.length()) Long.MAX_VALUE else total + file.length()
        }

    private fun isSafeTransactionId(id: String): Boolean =
        id.isNotBlank() && id == File(id).name && !id.contains("..")

    private fun PatchTransaction.isPreparedForRecovery(): Boolean =
        stage in RESUMABLE_STAGES &&
            signedArtifactNames.isNotEmpty() &&
            signedArtifactNames.distinct().size == signedArtifactNames.size &&
            signedArtifactNames.all { it == File(it).name } &&
            artifactDigests.size == signedArtifactNames.size &&
            artifactDigests.all { it.matches(SHA256_PATTERN) }

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val RESUMABLE_STAGES = setOf(
            PatchStage.AwaitingGameUninstall,
            PatchStage.AwaitingInstallPermission,
        )
        private val CLEANUP_STAGES = setOf(
            PatchStage.AwaitingGameUninstall,
            PatchStage.AwaitingInstallPermission,
            PatchStage.Completed,
            PatchStage.Failed,
        )
    }
}
