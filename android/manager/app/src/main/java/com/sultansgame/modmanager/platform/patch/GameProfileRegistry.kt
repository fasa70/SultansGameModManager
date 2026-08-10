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
            return unsupported(source, "包名不是目标游戏。")
        }
        if (REQUIRED_ABI !in base.supportedAbis) {
            return unsupported(source, "APK 不包含 $REQUIRED_ABI。")
        }
        val profile = profiles.firstOrNull { candidate ->
            candidate.isComplete() && (
                candidate.matchesVerified(base) ||
                    (trustedDeviceCertificateSha256 != null &&
                        base.packageName in candidate.packageNames &&
                        candidate.requiredAbi in base.supportedAbis &&
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

    private fun unsupported(source: PatchSource, reason: String) = PatchInputClassification(
        source = source,
        mode = PatchMode.Experimental,
        profileId = null,
        compatibility = CompatibilityReport(Compatibility.Unsupported, listOf(reason)),
    )

    private fun GameProfile.isComplete(): Boolean =
        loaderSplitName.isNotBlank() &&
            loaderTemplateSha256.isSha256() &&
            nativeLoaderSha256.isSha256()

    private fun String?.isSha256(): Boolean = this?.matches(SHA256_PATTERN) == true

    private companion object {
        const val TARGET_PACKAGE = "com.gametree.sultan.pd"
        const val REQUIRED_ABI = "arm64-v8a"
        val SHA256_PATTERN = Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE)
        val OFFICIAL_10005 = GameProfile(
            id = "official-android-2026-07-27",
            packageNames = setOf(TARGET_PACKAGE),
            signingDigestsSha256 = setOf("680da79f081f98e01d643ed3f001ae6b9111894111d73a9a9e6ebc2c58eb00d4"),
            requiredAbi = REQUIRED_ABI,
            versionCodes = setOf(10005L),
            loaderSplitName = "modloader",
            loaderTemplateSha256 = "80dc4e600ea58b272f36cfa81c830d12fc74e63276bed7f88a935f61e07693e3",
            nativeLoaderSha256 = "23ce7678ad665bb18a78e54ed1c65d23583384b5c67bad739cfae1961f2c0734",
            providerProtocolVersion = 2,
        )
    }
}
