package com.sultansgame.modmanager.platform.patch

import com.sultansgame.modmanager.model.ApkInspection
import com.sultansgame.modmanager.model.Compatibility
import com.sultansgame.modmanager.model.GameProfile
import com.sultansgame.modmanager.model.PatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class GameProfileRegistryTest {
    @Test
    fun classifiesFrozenOfficialProfileAsVerified() {
        val result = GameProfileRegistry().classify(PatchSource.SelectedApk, extractedSet(officialInspection()))

        assertEquals(Compatibility.Candidate, result.compatibility.compatibility)
        assertEquals("official-android-2026-07-27", result.profileId)
    }

    @Test
    fun acceptsAbiProvidedBySplit() {
        val result = GameProfileRegistry().classify(
            PatchSource.SelectedApks,
            extractedSet(officialInspection(supportedAbis = emptySet()), splitInspection("config.arm64_v8a")),
        )

        assertEquals(Compatibility.Candidate, result.compatibility.compatibility)
    }

    @Test
    fun rejectsUnknownVersionInsteadOfAllowingExperimentalSigning() {
        val result = GameProfileRegistry().classify(
            PatchSource.SelectedApk,
            extractedSet(officialInspection(versionCode = 10006L)),
        )

        assertEquals(Compatibility.Unsupported, result.compatibility.compatibility)
        assertNull(result.profileId)
    }

    @Test
    fun acceptsPreviouslyMigratedPackageWithTrustedDeviceCertificate() {
        val result = GameProfileRegistry().classify(
            PatchSource.InstalledGame,
            extractedSet(officialInspection(signer = DEVICE_CERTIFICATE)),
            trustedDeviceCertificateSha256 = DEVICE_CERTIFICATE,
        )

        assertEquals(Compatibility.Candidate, result.compatibility.compatibility)
    }

    @Test
    fun rejectsProfileMissingFrozenLoaderDigests() {
        val registry = GameProfileRegistry(
            profiles = listOf(
                GameProfile(
                    id = "incomplete",
                    packageNames = setOf("com.gametree.sultan.pd"),
                    signingDigestsSha256 = setOf(OFFICIAL_CERTIFICATE),
                    versionCodes = setOf(10005L),
                    loaderTemplateSha256 = null,
                    nativeLoaderSha256 = "a".repeat(64),
                ),
            ),
        )

        val result = registry.classify(PatchSource.SelectedApk, extractedSet(officialInspection()))

        assertEquals(Compatibility.Unsupported, result.compatibility.compatibility)
        assertNull(result.profileId)
    }

    private fun extractedSet(base: ApkInspection, vararg splits: ApkInspection): ExtractedApkSet =
        ExtractedApkSet(
            transactionId = "test",
            root = File("."),
            base = ExtractedApk(File("base.apk"), base, "a".repeat(64)),
            splits = splits.mapIndexed { index, inspection ->
                ExtractedApk(File("split-$index.apk"), inspection, "b".repeat(64))
            },
        )

    private fun officialInspection(
        versionCode: Long = 10005L,
        supportedAbis: Set<String> = setOf("arm64-v8a"),
        signer: String = OFFICIAL_CERTIFICATE,
    ) = ApkInspection(
        sourceLabel = "test.apk",
        packageName = "com.gametree.sultan.pd",
        versionCode = versionCode,
        versionName = "1.0.5",
        splitName = null,
        supportedAbis = supportedAbis,
        signerDigestsSha256 = setOf(signer),
        entryCount = 1,
        sizeBytes = 1,
        warnings = emptyList(),
    )

    private fun splitInspection(name: String) = officialInspection().copy(
        sourceLabel = "$name.apk",
        splitName = name,
        supportedAbis = setOf("arm64-v8a"),
    )

    private companion object {
        const val OFFICIAL_CERTIFICATE = "680da79f081f98e01d643ed3f001ae6b9111894111d73a9a9e6ebc2c58eb00d4"
        val DEVICE_CERTIFICATE = "a".repeat(64)
    }
}
