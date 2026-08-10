package com.sultansgame.modmanager.platform.patch

import com.sultansgame.modmanager.model.ApkInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignedArtifactInspectionTest {
    @Test
    fun acceptsMissingStandaloneSplitMetadata() {
        val input = inspection(packageName = "com.gametree.sultan.pd", versionCode = 10005L, splitName = "modloader")
        val parsed = inspection(
            packageName = null,
            versionCode = null,
            splitName = null,
            signerDigestsSha256 = setOf(CERTIFICATE),
        )

        assertNull(signedArtifactInspectionFailureReason(input, parsed, CERTIFICATE))
    }

    @Test
    fun rejectsPresentMetadataThatDoesNotMatchInput() {
        val input = inspection(packageName = "com.gametree.sultan.pd", versionCode = 10005L, splitName = "modloader")
        val parsed = inspection(
            packageName = input.packageName,
            versionCode = 10006L,
            splitName = input.splitName,
            signerDigestsSha256 = setOf(CERTIFICATE),
        )

        assertEquals(
            "签名后 版本号与输入不一致：解析值=10006，预期值=10005",
            signedArtifactInspectionFailureReason(input, parsed, CERTIFICATE),
        )
    }

    @Test
    fun rejectsUnexpectedCertificate() {
        val input = inspection(packageName = "com.gametree.sultan.pd", versionCode = 10005L, splitName = "modloader")
        val parsed = inspection(
            packageName = input.packageName,
            versionCode = input.versionCode,
            splitName = input.splitName,
            signerDigestsSha256 = setOf(OTHER_CERTIFICATE),
        )

        assertEquals(
            "签名证书与设备证书不一致",
            signedArtifactInspectionFailureReason(input, parsed, CERTIFICATE),
        )
    }

    private fun inspection(
        packageName: String?,
        versionCode: Long?,
        splitName: String?,
        signerDigestsSha256: Set<String> = emptySet(),
    ) = ApkInspection(
        sourceLabel = "test.apk",
        packageName = packageName,
        versionCode = versionCode,
        versionName = "1.0.5",
        splitName = splitName,
        supportedAbis = emptySet(),
        signerDigestsSha256 = signerDigestsSha256,
        entryCount = 1,
        sizeBytes = 1,
        warnings = emptyList(),
    )

    private companion object {
        const val CERTIFICATE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_CERTIFICATE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
