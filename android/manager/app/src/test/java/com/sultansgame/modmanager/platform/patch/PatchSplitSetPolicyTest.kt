package com.sultansgame.modmanager.platform.patch

import com.sultansgame.modmanager.model.ApkInspection
import com.sultansgame.modmanager.model.PatchArtifact
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PatchSplitSetPolicyTest {
    @Test
    fun removesExistingLoaderButKeepsPlayConfigSplits() {
        val loader = extracted("modloader")
        val abi = extracted("config.arm64_v8a", setOf("arm64-v8a"))
        val retained = PatchSplitSetPolicy.withoutExistingLoader(listOf(loader, abi), "modloader")

        assertEquals(listOf("config.arm64_v8a"), retained.map { it.inspection.splitName })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateExistingLoaders() {
        PatchSplitSetPolicy.withoutExistingLoader(listOf(extracted("modloader"), extracted("modloader")), "modloader")
    }

    @Test
    fun expectedNamesAreSortedAndRequireOneLoader() {
        val names = PatchSplitSetPolicy.expectedSplitNames(
            listOf(patch("z"), patch("modloader"), patch("config.arm64_v8a")),
            "modloader",
        )

        assertEquals(listOf("config.arm64_v8a", "modloader", "z"), names)
    }

    private fun extracted(name: String, abis: Set<String> = emptySet()) = ExtractedApk(
        file = File("$name.apk"),
        inspection = inspection(name, abis),
        sha256 = "a".repeat(64),
    )

    private fun patch(name: String) = PatchArtifact(
        fileName = "$name.apk",
        sha256 = "b".repeat(64),
        sizeBytes = 1L,
        inspection = inspection(name),
    )

    private fun inspection(name: String, abis: Set<String> = emptySet()) = ApkInspection(
        sourceLabel = name,
        packageName = "com.gametree.sultan.pd",
        versionCode = 10005L,
        versionName = "1.0.5",
        splitName = name,
        supportedAbis = abis,
        signerDigestsSha256 = setOf("c".repeat(64)),
        entryCount = 1,
        sizeBytes = 1L,
        warnings = emptyList(),
    )
}
