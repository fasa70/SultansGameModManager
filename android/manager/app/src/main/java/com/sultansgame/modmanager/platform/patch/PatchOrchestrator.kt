package com.sultansgame.modmanager.platform.patch

import android.content.Intent
import com.sultansgame.modmanager.model.PatchArtifact
import com.sultansgame.modmanager.model.PatchConfirmation
import com.sultansgame.modmanager.model.PatchFailure
import com.sultansgame.modmanager.model.PatchInstallPlan
import com.sultansgame.modmanager.model.PatchMode
import com.sultansgame.modmanager.model.PatchSource
import com.sultansgame.modmanager.model.PatchStage
import com.sultansgame.modmanager.platform.game.GameProbeResult
import com.sultansgame.modmanager.platform.game.PackageManagerGameProbe
import com.sultansgame.modmanager.split.LoaderSplitRequest
import com.sultansgame.modmanager.split.LoaderSplitResult
import com.sultansgame.modmanager.split.SplitArtifactFactory
import java.io.File

internal sealed interface PatchOrchestrationResult {
    data class AwaitingConfirmation(val mode: PatchMode, val reason: String) : PatchOrchestrationResult
    data class NeedsInstallPermission(val transactionId: String? = null) : PatchOrchestrationResult
    data class NeedsGameUninstall(val transactionId: String) : PatchOrchestrationResult
    data class ReadyToInstall(val transactionId: String, val summary: String) : PatchOrchestrationResult
    data class AwaitingSystemInstall(val transactionId: String, val sessionId: Int) : PatchOrchestrationResult
    data class NeedsUserAction(val transactionId: String, val intent: Intent) : PatchOrchestrationResult
    data class AwaitingVerification(val transactionId: String) : PatchOrchestrationResult
    data class Completed(val transactionId: String) : PatchOrchestrationResult
    data class Failed(val transactionId: String?, val failure: PatchFailure, val reason: String) : PatchOrchestrationResult
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
        trustedDeviceCertificateSha256: String? = null,
    ): PatchOrchestrationResult {
        val inputDigests = extracted.allArtifacts().map(ExtractedApk::sha256)
        transactions.write(
            PatchTransaction(
                id = extracted.transactionId,
                stage = PatchStage.InspectingInput,
                sessionId = null,
                artifactDigests = inputDigests,
            ),
        )
        val classification = profileRegistry.classify(source, extracted, trustedDeviceCertificateSha256)
        if (classification.compatibility.compatibility == com.sultansgame.modmanager.model.Compatibility.Unsupported) {
            return fail(extracted.transactionId, PatchFailure.UnsupportedInput, classification.compatibility.reasons.joinToString())
        }
        transactions.write(
            PatchTransaction(
                id = extracted.transactionId,
                stage = PatchStage.ClassifyingProfile,
                sessionId = null,
                artifactDigests = inputDigests,
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
                    artifactDigests = inputDigests,
                    mode = classification.mode,
                    profileId = classification.profileId,
                ),
            )
            return PatchOrchestrationResult.AwaitingConfirmation(
                classification.mode,
                "继续前必须确认安装风险${if (classification.mode == PatchMode.Experimental) "、已备份存档和兼容性风险" else "与密钥恢复限制"}。",
            )
        }
        if (keyStore.state() == com.sultansgame.modmanager.model.DeviceSigningKeyState.MissingAfterMigration) {
            return fail(extracted.transactionId, PatchFailure.DeviceKeyMissing, "设备签名密钥已丢失，必须卸载旧迁移版游戏后重新迁移。")
        }
        val identity = runCatching(keyStore::getOrCreate).getOrElse {
            return fail(extracted.transactionId, PatchFailure.DeviceKeyMissing, it.message ?: "无法读取设备签名密钥。")
        }
        if (trustedDeviceCertificateSha256 != null && trustedDeviceCertificateSha256 != identity.certificateSha256) {
            return fail(extracted.transactionId, PatchFailure.DeviceKeyMissing, "设备签名证书在导入和修补期间发生变化。")
        }
        val profile = profileRegistry.profile(classification.profileId)
            ?: return fail(extracted.transactionId, PatchFailure.UnsupportedInput, "找不到已分类的游戏 profile。")
        val retainedSplits = runCatching {
            PatchSplitSetPolicy.withoutExistingLoader(extracted.splits, profile.loaderSplitName)
        }.getOrElse {
            return fail(extracted.transactionId, PatchFailure.IncompletePackageSet, it.message ?: "输入 split 集合无效。")
        }
        transactions.write(
            PatchTransaction(
                id = extracted.transactionId,
                stage = PatchStage.PreparingArtifacts,
                sessionId = null,
                artifactDigests = inputDigests,
                mode = classification.mode,
                profileId = classification.profileId,
                expectedCertificateSha256 = identity.certificateSha256,
            ),
        )
        val signedDirectory = File(extracted.root.parentFile, "signed").apply { mkdirs() }
        val signedBase = sign(extracted.base, File(signedDirectory, "base.apk"), identity)
            ?: return fail(extracted.transactionId, PatchFailure.SigningUnavailable, "base APK 重签失败。")
        val signedSplits = retainedSplits.mapIndexed { index, artifact ->
            sign(artifact, File(signedDirectory, "split-$index.apk"), identity)
                ?: return fail(extracted.transactionId, PatchFailure.SigningUnavailable, "split APK 重签失败。")
        }
        val templateSha256 = profile.loaderTemplateSha256
            ?: return fail(extracted.transactionId, PatchFailure.SplitUnavailable, "尚未冻结匹配游戏 profile 的 loader split 模板摘要。")
        val nativeDigest = profile.nativeLoaderSha256
            ?: return fail(extracted.transactionId, PatchFailure.SplitUnavailable, "尚未冻结匹配游戏 profile 的 loader native 摘要。")
        val splitResult = splitFactoryForNativeDigest(nativeDigest).build(
            LoaderSplitRequest(
                targetApplicationId = requireNotNull(extracted.base.inspection.packageName),
                loaderSplitName = profile.loaderSplitName,
                loaderTemplateSha256 = templateSha256,
                target = extracted.base.inspection,
                templateOutputPath = File(extracted.root.parentFile, "template/modloader.apk").absolutePath,
            ),
        )
        val loader = when (splitResult) {
            is LoaderSplitResult.Built -> {
                if (splitResult.splitName != profile.loaderSplitName) {
                    return fail(extracted.transactionId, PatchFailure.SplitUnavailable, "生成的 loader split 名称不匹配 profile。")
                }
                sign(
                    ExtractedApk(
                        File(splitResult.artifact.path),
                        splitResult.artifact.inspection,
                        splitResult.artifact.sha256,
                    ),
                    File(signedDirectory, "modloader.apk"),
                    identity,
                ) ?: return fail(extracted.transactionId, PatchFailure.SigningUnavailable, "loader split 重签失败。")
            }
            is LoaderSplitResult.Unavailable -> return fail(extracted.transactionId, PatchFailure.SplitUnavailable, splitResult.reason)
        }
        val plan = PatchInstallPlan(
            transactionId = extracted.transactionId,
            source = source,
            mode = classification.mode,
            base = signedBase.toPatchArtifact(),
            splits = (signedSplits + loader).map { it.toPatchArtifact() },
            deviceCertificateSha256 = identity.certificateSha256,
            confirmation = confirmation,
            profileId = classification.profileId,
        )
        val expectedSplitNames = runCatching {
            PatchSplitSetPolicy.expectedSplitNames(plan.splits, profile.loaderSplitName)
        }.getOrElse {
            return fail(extracted.transactionId, PatchFailure.IncompletePackageSet, it.message ?: "最终安装集合无效。")
        }
        transactions.write(
            PatchTransaction(
                id = extracted.transactionId,
                stage = PatchStage.AwaitingGameUninstall,
                sessionId = null,
                artifactDigests = plan.artifacts.map(PatchArtifact::sha256),
                mode = plan.mode,
                profileId = plan.profileId,
                expectedCertificateSha256 = identity.certificateSha256,
                expectedVersionCode = signedBase.inspection.versionCode,
                expectedSplitNames = expectedSplitNames,
                signedArtifactNames = plan.artifacts.map(PatchArtifact::fileName),
            ),
        )
        return installationReadiness(
            transactionId = extracted.transactionId,
            expectedVersionCode = signedBase.inspection.versionCode,
            expectedCertificateSha256 = identity.certificateSha256,
        )
    }

    fun resumePreparedArtifacts(transactionId: String): PatchOrchestrationResult {
        val transaction = transactions.read(transactionId)
            ?: return PatchOrchestrationResult.Failed(null, PatchFailure.InternalError, "找不到已准备的修补事务。")
        if (transaction.stage !in setOf(PatchStage.AwaitingGameUninstall, PatchStage.AwaitingInstallPermission)) {
            return PatchOrchestrationResult.Failed(transactionId, PatchFailure.InternalError, "修补事务不处于可恢复的已准备阶段。")
        }
        when (val validation = validatePreparedArtifacts(transaction)) {
            is PreparedArtifactsValidation.Invalid -> return fail(transactionId, validation.failure, validation.reason)
            is PreparedArtifactsValidation.Valid -> Unit
        }
        return installationReadiness(
            transactionId = transactionId,
            expectedVersionCode = transaction.expectedVersionCode,
            expectedCertificateSha256 = transaction.expectedCertificateSha256,
        )
    }

    fun submitPreparedArtifacts(transactionId: String): PatchOrchestrationResult {
        val transaction = transactions.read(transactionId)
            ?: return PatchOrchestrationResult.Failed(null, PatchFailure.InternalError, "找不到待安装的修补事务。")
        if (transaction.stage !in setOf(PatchStage.AwaitingGameUninstall, PatchStage.AwaitingInstallPermission)) {
            return fail(transactionId, PatchFailure.InternalError, "修补事务不处于可安装阶段。")
        }
        when (val probe = gameProbe.probe()) {
            is GameProbeResult.Found -> {
                if (!isSameDeviceInstallation(probe.snapshot.versionCode, probe.snapshot.signerDigestsSha256, transaction.expectedVersionCode, transaction.expectedCertificateSha256)) {
                    return PatchOrchestrationResult.NeedsGameUninstall(transactionId)
                }
            }
            is GameProbeResult.Failed -> return fail(transactionId, PatchFailure.SystemInstallFailed, "无法确认当前游戏状态。")
            GameProbeResult.NotInstalled -> Unit
        }
        if (!installer.canRequestInstalls()) {
            transactions.write(transaction.copy(stage = PatchStage.AwaitingInstallPermission))
            return PatchOrchestrationResult.NeedsInstallPermission(transactionId)
        }
        val artifacts = when (val validation = validatePreparedArtifacts(transaction)) {
            is PreparedArtifactsValidation.Valid -> validation.artifacts
            is PreparedArtifactsValidation.Invalid -> return fail(transactionId, validation.failure, validation.reason)
        }
        return when (val submission = installer.submit(transactionId, artifacts)) {
            is PackageInstallSubmission.Submitted -> {
                transactions.write(transaction.copy(stage = PatchStage.AwaitingSystemInstall, sessionId = submission.sessionId))
                PatchOrchestrationResult.AwaitingSystemInstall(transactionId, submission.sessionId)
            }
            is PackageInstallSubmission.Unavailable -> PatchOrchestrationResult.NeedsInstallPermission(transactionId)
            is PackageInstallSubmission.Failed -> fail(transactionId, PatchFailure.SystemInstallFailed, submission.reason)
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
        if (!status.succeeded) return fail(transactionId, PatchFailure.SystemInstallFailed, status.message ?: "系统安装失败或被取消。")
        val expectedCertificate = transaction.expectedCertificateSha256
            ?: return fail(transactionId, PatchFailure.PostInstallVerificationFailed, "安装事务缺少设备证书预期值。")
        val expectedVersion = transaction.expectedVersionCode
            ?: return fail(transactionId, PatchFailure.PostInstallVerificationFailed, "安装事务缺少版本预期值。")
        transactions.write(transaction.copy(stage = PatchStage.VerifyingInstall))
        if (!gameProbe.verifiesMigration(expectedVersion, expectedCertificate, transaction.expectedSplitNames.toSet())) {
            return fail(transactionId, PatchFailure.PostInstallVerificationFailed, "安装后包名、版本、设备证书或 split 集合未通过验证。")
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

    private fun installationReadiness(
        transactionId: String,
        expectedVersionCode: Long?,
        expectedCertificateSha256: String?,
    ): PatchOrchestrationResult {
        val probe = gameProbe.probe()
        return when (probe) {
            GameProbeResult.NotInstalled -> if (!installer.canRequestInstalls()) {
                transactions.read(transactionId)?.let { transactions.write(it.copy(stage = PatchStage.AwaitingInstallPermission)) }
                PatchOrchestrationResult.NeedsInstallPermission(transactionId)
            } else PatchOrchestrationResult.ReadyToInstall(transactionId, "已确认当前游戏未安装；可安装已准备的修补工件。")
            is GameProbeResult.Failed -> fail(transactionId, PatchFailure.SystemInstallFailed, "无法确认当前游戏状态。")
            is GameProbeResult.Found -> if (isSameDeviceInstallation(probe.snapshot.versionCode, probe.snapshot.signerDigestsSha256, expectedVersionCode, expectedCertificateSha256)) {
                PatchOrchestrationResult.ReadyToInstall(transactionId, "已检测到同一设备签名的游戏；可直接覆盖安装更新。")
            } else PatchOrchestrationResult.NeedsGameUninstall(transactionId)
        }
    }

    private fun isSameDeviceInstallation(
        installedVersionCode: Long,
        installedCertificate: Set<String>,
        expectedVersionCode: Long?,
        expectedCertificate: String?,
    ): Boolean = expectedVersionCode != null && expectedCertificate != null &&
        installedVersionCode == expectedVersionCode && installedCertificate == setOf(expectedCertificate)

    private fun validatePreparedArtifacts(transaction: PatchTransaction): PreparedArtifactsValidation {
        val expectedCertificate = transaction.expectedCertificateSha256
            ?: return PreparedArtifactsValidation.Invalid(PatchFailure.DeviceKeyMissing, "修补事务缺少设备签名证书。")
        if (keyStore.state() == com.sultansgame.modmanager.model.DeviceSigningKeyState.MissingAfterMigration) {
            return PreparedArtifactsValidation.Invalid(PatchFailure.DeviceKeyMissing, "设备签名密钥已丢失，不能继续安装已准备的工件。")
        }
        if (runCatching(keyStore::certificateSha256).getOrNull() != expectedCertificate) {
            return PreparedArtifactsValidation.Invalid(PatchFailure.DeviceKeyMissing, "当前 Android Keystore 与已准备工件的签名证书不一致。")
        }
        val artifactNames = transaction.signedArtifactNames
        if (artifactNames.isEmpty() || artifactNames.distinct().size != artifactNames.size || transaction.artifactDigests.size != artifactNames.size) {
            return PreparedArtifactsValidation.Invalid(PatchFailure.InternalError, "修补事务缺少完整的签名 APK 集合。")
        }
        val expectedSplits = transaction.expectedSplitNames
        if (expectedSplits.any(String::isBlank) || expectedSplits.distinct().size != expectedSplits.size || expectedSplits.size != artifactNames.size - 1) {
            return PreparedArtifactsValidation.Invalid(PatchFailure.InternalError, "修补事务 split 集合无效。")
        }
        val signedDirectory = File(transactions.root(transaction.id), "signed")
        val artifacts = artifactNames.map { name ->
            if (name != File(name).name) return PreparedArtifactsValidation.Invalid(PatchFailure.InternalError, "签名 APK 文件名无效。")
            File(signedDirectory, name)
        }
        if (artifacts.any { !it.isFile }) return PreparedArtifactsValidation.Invalid(PatchFailure.InternalError, "签名 APK 暂存文件不完整。")
        val digests = artifacts.map { artifact -> com.sultansgame.modmanager.apk.ReadOnlyApkInspector().sha256 { artifact.inputStream() } }
        if (digests != transaction.artifactDigests) return PreparedArtifactsValidation.Invalid(PatchFailure.InternalError, "签名 APK 暂存文件摘要不匹配。")
        return PreparedArtifactsValidation.Valid(artifacts)
    }

    private sealed interface PreparedArtifactsValidation {
        data class Valid(val artifacts: List<File>) : PreparedArtifactsValidation
        data class Invalid(val failure: PatchFailure, val reason: String) : PreparedArtifactsValidation
    }

    private fun sign(input: ExtractedApk, output: File, identity: DeviceSigningIdentity): ExtractedApk? {
        val result = signer.sign(input.file, output, identity)
        if (result !is ApkSigningResult.Signed || !result.verifiedV1 || !result.verifiedV2) return null
        val parsed = archiveInspector.inspect(output, output.name)
        if (parsed.signerDigestsSha256 != setOf(identity.certificateSha256)) return null
        if ((parsed.packageName != null && parsed.packageName != input.inspection.packageName) ||
            (parsed.versionCode != null && parsed.versionCode != input.inspection.versionCode) ||
            (parsed.splitName != null && parsed.splitName != input.inspection.splitName)
        ) return null
        val inspection = parsed.copy(
            packageName = input.inspection.packageName,
            versionCode = input.inspection.versionCode,
            versionName = input.inspection.versionName,
            splitName = input.inspection.splitName,
        )
        return ExtractedApk(output, inspection, com.sultansgame.modmanager.apk.ReadOnlyApkInspector().sha256 { output.inputStream() })
    }

    private fun ExtractedApk.toPatchArtifact() = PatchArtifact(file.name, sha256, file.length(), inspection)
    private fun ExtractedApkSet.allArtifacts(): List<ExtractedApk> = listOf(base) + splits
    private fun fail(transactionId: String, failure: PatchFailure, reason: String): PatchOrchestrationResult.Failed {
        transactions.read(transactionId)?.let { transactions.write(it.copy(stage = PatchStage.Failed, failure = "$failure:$reason")) }
        return PatchOrchestrationResult.Failed(transactionId, failure, reason)
    }
}
