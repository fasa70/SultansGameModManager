package com.sultansgame.modmanager.platform.patch

import com.sultansgame.modmanager.model.ApkInspection
import com.sultansgame.modmanager.model.Compatibility
import com.sultansgame.modmanager.model.CompatibilityReport
import com.sultansgame.modmanager.model.GameProfile
import com.sultansgame.modmanager.model.PatchInputClassification
import com.sultansgame.modmanager.model.PatchMode
import com.sultansgame.modmanager.model.PatchSource

internal class GameProfileRegistry(
    private val profiles: List<GameProfile> = listOf(OFFICIAL_10005),
) {
    fun profile(id: String?): GameProfile? = id?.let { requested ->
        profiles.firstOrNull { candidate -> candidate.id == requested }
    }

    fun classify(
        source: PatchSource,
        extracted: ExtractedApkSet,
        trustedDeviceCertificateSha256: String? = null,
    ): PatchInputClassification {
        val base = extracted.base.inspection
        if (base.packageName != TARGET_PACKAGE) {
            return unsupported(source, "包名不是目标游戏。")
        }
        if (REQUIRED_ABI !in extracted.supportedAbis) {
            return unsupported(source, "安装集合不包含 $REQUIRED_ABI。")
        }
        val profile = profiles.firstOrNull { candidate ->
            candidate.isComplete() && (
                candidate.matchesVerified(base, extracted.supportedAbis) ||
                    (trustedDeviceCertificateSha256 != null &&
                        base.packageName in candidate.packageNames &&
                        candidate.requiredAbi in extracted.supportedAbis &&
                        base.versionCode in candidate.versionCodes &&
                        trustedDeviceCertificateSha256 in base.signerDigestsSha256)
                )
        }
        return if (profile != null) {
            PatchInputClassification(
                source = source,
                mode = PatchMode.Verified,
                profileId = profile.id,
                compatibility = CompatibilityReport(Compatibility.Candidate, emptyList()),
            )
        } else {
            unsupported(source, "未命中可安全修补的已冻结游戏 profile。")
        }
    }

    @Deprecated("Use the complete APK set overload")
    fun classify(
        source: PatchSource,
        base: ApkInspection,
        trustedDeviceCertificateSha256: String? = null,
    ): PatchInputClassification = classify(
        source,
        ExtractedApkSet(
            transactionId = "legacy",
            root = java.io.File("."),
            base = ExtractedApk(java.io.File("."), base, ""),
            splits = emptyList(),
        ),
        trustedDeviceCertificateSha256,
    )

    private fun unsupported(source: PatchSource, reason: String) = PatchInputClassification(
        source = source,
        mode = PatchMode.Experimental,
        profileId = null,
        compatibility = CompatibilityReport(Compatibility.Unsupported, listOf(reason)),
    )

    private fun GameProfile.isComplete(): Boolean =
        loaderSplitName.isNotBlank() &&
            packageNames.isNotEmpty() &&
            versionCodes.isNotEmpty() &&
            signingDigestsSha256.isNotEmpty()

    private companion object {
        const val TARGET_PACKAGE = "com.gametree.sultan.pd"
        const val REQUIRED_ABI = "arm64-v8a"
        val OFFICIAL_10005 = GameProfile(
            id = "official-android-2026-07-27",
            packageNames = setOf(TARGET_PACKAGE),
            signingDigestsSha256 = setOf("680da79f081f98e01d643ed3f001ae6b9111894111d73a9a9e6ebc2c58eb00d4"),
            requiredAbi = REQUIRED_ABI,
            versionCodes = setOf(10005L),
            loaderSplitName = "modloader",
            providerProtocolVersion = 2,
        )
    }
}
