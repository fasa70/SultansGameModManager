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
        base: ApkInspection,
        trustedDeviceCertificateSha256: String? = null,
    ): PatchInputClassification {
        if (base.packageName != TARGET_PACKAGE) {
            return PatchInputClassification(
                source = source,
                mode = PatchMode.Experimental,
                profileId = null,
                compatibility = CompatibilityReport(
                    Compatibility.Unsupported,
                    listOf("包名不是目标游戏。"),
                ),
            )
        }
        if (REQUIRED_ABI !in base.supportedAbis) {
            return PatchInputClassification(
                source = source,
                mode = PatchMode.Experimental,
                profileId = null,
                compatibility = CompatibilityReport(
                    Compatibility.Unsupported,
                    listOf("APK 不包含 $REQUIRED_ABI。"),
                ),
            )
        }
        val profile = profiles.firstOrNull { candidate ->
            candidate.matchesVerified(base) ||
                (trustedDeviceCertificateSha256 != null &&
                    base.packageName in candidate.packageNames &&
                    candidate.requiredAbi in base.supportedAbis &&
                    base.versionCode in candidate.versionCodes &&
                    trustedDeviceCertificateSha256 in base.signerDigestsSha256)
        }
        return if (profile != null) {
            PatchInputClassification(
                source = source,
                mode = PatchMode.Verified,
                profileId = profile.id,
                compatibility = CompatibilityReport(Compatibility.Candidate, emptyList()),
            )
        } else {
            PatchInputClassification(
                source = source,
                mode = PatchMode.Experimental,
                profileId = null,
                compatibility = CompatibilityReport(
                    Compatibility.Unverified,
                    listOf("未命中已冻结的官方 profile；兼容性未经验证，请确认已备份存档。"),
                ),
            )
        }
    }

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
            loaderTemplateSha256 = "f7cf7b49ff340a091b65bc6238cc109e9ea6047874be03f5a4aa436fc3d13517",
            nativeLoaderSha256 = "23ce7678ad665bb18a78e54ed1c65d23583384b5c67bad739cfae1961f2c0734",
            providerProtocolVersion = 1,
        )
    }
}
