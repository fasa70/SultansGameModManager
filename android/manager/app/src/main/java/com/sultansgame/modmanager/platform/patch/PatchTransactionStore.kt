package com.sultansgame.modmanager.platform.patch

import android.content.Context
import com.sultansgame.modmanager.model.PatchInstallMode
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
    val sourceSplitNames: List<String> = emptyList(),
    val installMode: PatchInstallMode? = null,
    val signedArtifactNames: List<String> = emptyList(),
    val failure: String? = null,
)

internal data class PatchWorkspaceCleanupSummary(
    val workspaceIds: Set<String>,
    val sizeBytes: Long,
)

internal sealed interface PatchWorkspaceCleanupResult {
    data class Deleted(
        val workspaceIds: Set<String>,
        val releasedBytes: Long,
    ) : PatchWorkspaceCleanupResult

    data object NothingToDelete : PatchWorkspaceCleanupResult
    data class Failed(val reason: String) : PatchWorkspaceCleanupResult
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

    fun cleanupSummary(reservedWorkspaceIds: Set<String>): PatchWorkspaceCleanupSummary? {
        val candidates = cleanupWorkspaces(reservedWorkspaceIds)
        val sizeBytes = candidates.fold(0L) { total, directory -> saturatedAdd(total, directorySize(directory)) }
        return candidates.takeIf { sizeBytes > 0L }?.let { directories ->
            PatchWorkspaceCleanupSummary(
                workspaceIds = directories.mapTo(linkedSetOf()) { it.name },
                sizeBytes = sizeBytes,
            )
        }
    }

    fun deleteCleanupWorkspaces(reservedWorkspaceIds: Set<String>): PatchWorkspaceCleanupResult {
        val candidates = cleanupWorkspaces(reservedWorkspaceIds)
        if (candidates.isEmpty()) return PatchWorkspaceCleanupResult.NothingToDelete

        val releasedBytes = candidates.fold(0L) { total, directory -> saturatedAdd(total, directorySize(directory)) }
        val deleted = linkedSetOf<String>()
        candidates.forEach { directory ->
            val deletedDirectory = runCatching { directory.deleteRecursively() }.getOrDefault(false)
            if (!deletedDirectory) {
                return PatchWorkspaceCleanupResult.Failed("部分私有修补文件未能删除。")
            }
            deleted += directory.name
        }
        return PatchWorkspaceCleanupResult.Deleted(deleted, releasedBytes)
    }

    fun sessionIds(): List<Int> = root.listFiles()
        .orEmpty()
        .filter(File::isDirectory)
        .mapNotNull { read(it.name)?.sessionId }

    fun resetAll(): List<String> {
        if (!root.exists()) return emptyList()
        val failures = mutableListOf<String>()
        root.listFiles().orEmpty().filter(File::isDirectory).forEach { directory ->
            val deleted = runCatching { directory.deleteRecursively() }.getOrDefault(false)
            if (!deleted || directory.exists()) failures += directory.name
        }
        return failures
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
            setProperty("sourceSplitNames", transaction.sourceSplitNames.joinToString(","))
            transaction.installMode?.let { setProperty("installMode", it.name) }
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
                    sourceSplitNames = getProperty("sourceSplitNames").orEmpty().split(',').filter(String::isNotBlank),
                    installMode = getProperty("installMode")?.let(PatchInstallMode::valueOf),
                    signedArtifactNames = getProperty("signedArtifacts").orEmpty().split(',').filter(String::isNotBlank),
                    failure = getProperty("failure"),
                )
            }
        }.getOrNull()
    }

    private fun cleanupWorkspaces(reservedWorkspaceIds: Set<String>): List<File> = root.listFiles()
        .orEmpty()
        .filter { directory ->
            directory.isDirectory &&
                directory.name !in reservedWorkspaceIds &&
                isDirectChildOfRoot(directory) &&
                read(directory.name)?.stage != PatchStage.AwaitingSystemInstall
        }
        .filter { directorySize(it) > 0L }

    private fun isDirectChildOfRoot(directory: File): Boolean = runCatching {
        directory.canonicalFile.parentFile == root.canonicalFile
    }.getOrDefault(false)

    private fun directorySize(directory: File): Long = directory.walkTopDown()
        .filter(File::isFile)
        .fold(0L) { total, file -> saturatedAdd(total, file.length()) }

    private fun saturatedAdd(total: Long, additional: Long): Long =
        if (Long.MAX_VALUE - total < additional) Long.MAX_VALUE else total + additional

    private fun PatchTransaction.isPreparedForRecovery(): Boolean =
        stage in RESUMABLE_STAGES &&
            signedArtifactNames.isNotEmpty() &&
            signedArtifactNames.distinct().size == signedArtifactNames.size &&
            signedArtifactNames.all { it == File(it).name } &&
            artifactDigests.size == signedArtifactNames.size &&
            artifactDigests.all { it.matches(SHA256_PATTERN) } &&
            expectedSplitNames.size == signedArtifactNames.size - 1 &&
            expectedSplitNames.distinct().size == expectedSplitNames.size &&
            expectedSplitNames.none(String::isBlank) &&
            sourceSplitNames.distinct().size == sourceSplitNames.size &&
            sourceSplitNames.none(String::isBlank) &&
            expectedCertificateSha256?.matches(SHA256_PATTERN) == true &&
            expectedVersionCode != null &&
            installMode != null

    private companion object {
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val RESUMABLE_STAGES = setOf(
            PatchStage.AwaitingGameUninstall,
            PatchStage.AwaitingInstallPermission,
        )
    }
}
