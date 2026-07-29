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

internal class PatchTransactionStore(context: Context) {
    private val root = File(context.filesDir, "patch-staging")

    fun root(transactionId: String): File = File(root, transactionId)

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
}
