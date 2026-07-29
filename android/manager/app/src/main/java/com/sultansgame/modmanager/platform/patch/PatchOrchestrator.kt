package com.sultansgame.modmanager.platform.patch

import android.content.Intent
import com.sultansgame.modmanager.model.ApkInspection
import com.sultansgame.modmanager.model.PatchArtifact
import com.sultansgame.modmanager.model.PatchConfirmation
import com.sultansgame.modmanager.model.PatchFailure
import com.sultansgame.modmanager.model.PatchInstallPlan
import com.sultansgame.modmanager.model.PatchMode
import com.sultansgame.modmanager.model.PatchSource
import com.sultansgame.modmanager.model.PatchStage
import com.sultansgame.modmanager.split.LoaderSplitRequest
import com.sultansgame.modmanager.split.LoaderSplitResult
import com.sultansgame.modmanager.platform.game.PackageManagerGameProbe
import com.sultansgame.modmanager.split.SplitArtifactFactory
import java.io.File

internal sealed interface PatchOrchestrationResult {
    data class AwaitingConfirmation(
        val mode: PatchMode,
        val reason: String,
    ) : PatchOrchestrationResult

    data class AwaitingSystemInstall(
        val plan: PatchInstallPlan,
        val sessionId: Int,
    ) : PatchOrchestrationResult

    data class NeedsUserAction(
        val transactionId: String,
        val intent: Intent,
    ) : PatchOrchestrationResult

    data class AwaitingVerification(val transactionId: String) : PatchOrchestrationResult

    data class Completed(val transactionId: String) : PatchOrchestrationResult

    data class Failed(
        val transactionId: String?,
        val failure: PatchFailure,
        val reason: String,
    ) : PatchOrchestrationResult
}

internal class PatchOrchestrator(
    private val keyStore: DeviceSigningKeyStore,
    private val profileRegistry: GameProfileRegistry,
    private val signer: ApkSigningEngine,
    private val installer: PackageInstallerGateway,
    private val transactions: PatchTransactionStore,
    private val archiveInspector: AndroidApkArchiveInspector,
    private val gameProbe: PackageManagerGameProbe,
    private val splitFactoryForNativeDigest: (String) -> SplitArtifactFactory,
) {
    fun submit(
        source: PatchSource,
        extracted: ExtractedApkSet,
        confirmation: PatchConfirmation,
    ): PatchOrchestrationResult {
        transactions.write(
            PatchTransaction(
                id = extracted.transactionId,
                stage = PatchStage.InspectingInput,
                sessionId = null,
                artifactDigests = extracted.allArtifacts().map(ExtractedApk::sha256),
            ),
        )
        val classification = profileRegistry.classify(source, extracted.base.inspection)
        if (classification.compatibility.compatibility == com.sultansgame.modmanager.model.Compatibility.Unsupported) {
            return fail(extracted.transactionId, PatchFailure.UnsupportedInput, classification.compatibility.reasons.joinToString())
        }
        transactions.write(
            PatchTransaction(
                id = extracted.transactionId,
                stage = PatchStage.ClassifyingProfile,
                sessionId = null,
                artifactDigests = extracted.allArtifacts().map(ExtractedApk::sha256),
                mode = classification.mode,
                profileId = classification.profileId,
            ),
        )
        if (!confirmation.permits(classification.mode)) {
            transactions.write(
                PatchTransaction(
                    id = extracted.transactionId,
                    stage = PatchStage.AwaitingConfirmation,
                    sessionId = null,
                    artifactDigests = extracted.allArtifacts().map(ExtractedApk::sha256),
                    mode = classification.mode,
                    profileId = classification.profileId,
                ),
            )
            return PatchOrchestrationResult.AwaitingConfirmation(
                classification.mode,
                "继续前必须确认安装风险${if (classification.mode == PatchMode.Experimental) "、已备份存档和兼容性风险" else "与密钥恢复限制"}。",
            )
        }
        if (!installer.canRequestInstalls()) {
            return fail(extracted.transactionId, PatchFailure.InstallPermissionMissing, "尚未授予安装未知应用权限。")
        }
        if (keyStore.state() == com.sultansgame.modmanager.model.DeviceSigningKeyState.MissingAfterMigration) {
            return fail(extracted.transactionId, PatchFailure.DeviceKeyMissing, "设备签名密钥已丢失，必须卸载旧迁移版游戏后重新迁移。")
        }
        val identity = runCatching(keyStore::getOrCreate).getOrElse {
            return fail(extracted.transactionId, PatchFailure.DeviceKeyMissing, it.message ?: "无法读取设备签名密钥。")
        }
        transactions.write(
            PatchTransaction(
                id = extracted.transactionId,
                stage = PatchStage.PreparingArtifacts,
                sessionId = null,
                artifactDigests = extracted.allArtifacts().map(ExtractedApk::sha256),
                mode = classification.mode,
                profileId = classification.profileId,
                expectedCertificateSha256 = identity.certificateSha256,
            ),
        )
        val signedDirectory = File(extracted.root.parentFile, "signed").apply { mkdirs() }
        val signedBase = sign(extracted.base, File(signedDirectory, "base.apk"), identity)
            ?: return fail(extracted.transactionId, PatchFailure.SigningUnavailable, "base APK 重签失败。")
        val signedSplits = extracted.splits.mapIndexed { index, artifact ->
            sign(artifact, File(signedDirectory, "split-$index.apk"), identity)
                ?: return fail(extracted.transactionId, PatchFailure.SigningUnavailable, "split APK 重签失败。")
        }
        val profile = profileRegistry.profile(classification.profileId)
        val templateSha256 = profile?.loaderTemplateSha256
            ?: return fail(
                extracted.transactionId,
                PatchFailure.SplitUnavailable,
                "尚未冻结匹配游戏 profile 的 loader split 模板摘要。",
            )
        val nativeDigest = profile.nativeLoaderSha256
            ?: return fail(
                extracted.transactionId,
                PatchFailure.SplitUnavailable,
                "尚未冻结匹配游戏 profile 的 loader native 摘要。",
            )
        val splitResult = splitFactoryForNativeDigest(nativeDigest).build(
            LoaderSplitRequest(
                targetApplicationId = requireNotNull(extracted.base.inspection.packageName),
                loaderTemplateSha256 = templateSha256,
                target = extracted.base.inspection,
            ),
        )
        val loader = when (splitResult) {
            is LoaderSplitResult.Built -> sign(
                ExtractedApk(File(splitResult.artifact.path), splitResult.artifact.inspection, splitResult.artifact.sha256),
                File(signedDirectory, "modloader.apk"),
                identity,
            ) ?: return fail(extracted.transactionId, PatchFailure.SigningUnavailable, "loader split 重签失败。")
            is LoaderSplitResult.Unavailable -> return fail(extracted.transactionId, PatchFailure.SplitUnavailable, splitResult.reason)
        }
        val plan = PatchInstallPlan(
            transactionId = extracted.transactionId,
            source = source,
            mode = classification.mode,
            base = signedBase.toPatchArtifact(),
            splits = (signedSplits + loader).map { artifact -> artifact.toPatchArtifact() },
            deviceCertificateSha256 = identity.certificateSha256,
            confirmation = confirmation,
            profileId = classification.profileId,
        )
        val submission = installer.submit(extracted.transactionId, plan.artifacts.map { File(signedDirectory, it.fileName) })
        return when (submission) {
            is PackageInstallSubmission.Submitted -> {
                transactions.write(
                    PatchTransaction(
                        id = extracted.transactionId,
                        stage = PatchStage.AwaitingSystemInstall,
                        sessionId = submission.sessionId,
                        artifactDigests = plan.artifacts.map(PatchArtifact::sha256),
                        mode = plan.mode,
                        profileId = plan.profileId,
                        expectedCertificateSha256 = identity.certificateSha256,
                        expectedVersionCode = signedBase.inspection.versionCode,
                        expectedSplitNames = plan.splits.mapNotNull { it.inspection.splitName },
                    ),
                )
                PatchOrchestrationResult.AwaitingSystemInstall(plan, submission.sessionId)
            }
            is PackageInstallSubmission.Unavailable -> fail(extracted.transactionId, PatchFailure.InstallPermissionMissing, submission.reason)
            is PackageInstallSubmission.Failed -> fail(extracted.transactionId, PatchFailure.SystemInstallFailed, submission.reason)
        }
    }

    fun handleInstallResult(intent: Intent): PatchOrchestrationResult? {
        val transactionId = intent.getStringExtra(PackageInstallerGateway.EXTRA_TRANSACTION_ID) ?: return null
        val transaction = transactions.read(transactionId) ?: return null
        val status = installer.parseStatus(intent) ?: return null
        if (transaction.sessionId != status.sessionId) return null
        if (status.requiresUserAction) {
            return status.userActionIntent?.let { PatchOrchestrationResult.NeedsUserAction(transactionId, it) }
                ?: fail(transactionId, PatchFailure.SystemInstallFailed, "系统未提供安装确认 Intent。")
        }
        if (!status.succeeded) {
            return fail(transactionId, PatchFailure.SystemInstallFailed, status.message ?: "系统安装失败或被取消。")
        }
        val expectedCertificate = transaction.expectedCertificateSha256
            ?: return fail(transactionId, PatchFailure.PostInstallVerificationFailed, "安装事务缺少设备证书预期值。")
        val expectedVersion = transaction.expectedVersionCode
            ?: return fail(transactionId, PatchFailure.PostInstallVerificationFailed, "安装事务缺少版本预期值。")
        transactions.write(transaction.copy(stage = PatchStage.VerifyingInstall))
        if (!gameProbe.verifiesMigration(
                expectedVersion,
                expectedCertificate,
                transaction.expectedSplitNames.toSet(),
            )) {
            return fail(
                transactionId,
                PatchFailure.PostInstallVerificationFailed,
                "安装后包名、版本、设备证书或 split 集合未通过验证。",
            )
        }
        val identity = runCatching(keyStore::getOrCreate).getOrElse {
            return fail(transactionId, PatchFailure.DeviceKeyMissing, it.message ?: "无法读取设备签名密钥。")
        }
        if (identity.certificateSha256 != expectedCertificate) {
            return fail(transactionId, PatchFailure.PostInstallVerificationFailed, "安装后设备证书与当前 Android Keystore 不一致。")
        }
        keyStore.markMigrationCompleted(transactionId, identity)
        transactions.write(transaction.copy(stage = PatchStage.Completed))
        return PatchOrchestrationResult.Completed(transactionId)
    }

    private fun sign(input: ExtractedApk, output: File, identity: DeviceSigningIdentity): ExtractedApk? {
        val result = signer.sign(input.file, output, identity)
        if (result !is ApkSigningResult.Signed || !result.verifiedV1 || !result.verifiedV2) return null
        val parsed = archiveInspector.inspect(output, output.name)
        if (parsed.signerDigestsSha256 != setOf(identity.certificateSha256)) return null
        if ((parsed.packageName != null && parsed.packageName != input.inspection.packageName) ||
            (parsed.versionCode != null && parsed.versionCode != input.inspection.versionCode) ||
            (parsed.splitName != null && parsed.splitName != input.inspection.splitName)) return null
        val inspection = parsed.copy(
            packageName = input.inspection.packageName,
            versionCode = input.inspection.versionCode,
            versionName = input.inspection.versionName,
            splitName = input.inspection.splitName,
        )
        return ExtractedApk(
            output,
            inspection,
            com.sultansgame.modmanager.apk.ReadOnlyApkInspector().sha256 { output.inputStream() },
        )
    }

    private fun ExtractedApk.toPatchArtifact() = PatchArtifact(file.name, sha256, file.length(), inspection)

    private fun ExtractedApkSet.allArtifacts(): List<ExtractedApk> = listOf(base) + splits

    private fun fail(transactionId: String, failure: PatchFailure, reason: String): PatchOrchestrationResult.Failed {
        val previous = transactions.read(transactionId)
        if (previous != null) transactions.write(previous.copy(stage = PatchStage.Failed, failure = "$failure:$reason"))
        return PatchOrchestrationResult.Failed(transactionId, failure, reason)
    }
}
