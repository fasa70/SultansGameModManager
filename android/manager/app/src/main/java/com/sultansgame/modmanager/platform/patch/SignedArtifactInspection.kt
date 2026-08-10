package com.sultansgame.modmanager.platform.patch

import com.sultansgame.modmanager.model.ApkInspection

/**
 * Validates the identity that can be recovered from a signed APK archive.
 *
 * PackageManager may not expose metadata for a standalone split APK. A value
 * that is present must still match the trusted input; only an absent value is
 * tolerated here because the input payload was already validated before sign.
 */
internal fun signedArtifactInspectionFailureReason(
    input: ApkInspection,
    parsed: ApkInspection,
    expectedCertificateSha256: String,
): String? {
    if (parsed.signerDigestsSha256 != setOf(expectedCertificateSha256)) {
        return "签名证书与设备证书不一致"
    }
    val metadataMismatch = listOf(
        "包名" to (parsed.packageName to input.packageName),
        "版本号" to (parsed.versionCode to input.versionCode),
        "split 名称" to (parsed.splitName to input.splitName),
    ).firstOrNull { (_, values) -> values.first != null && values.first != values.second }
        ?: return null
    return "签名后 ${metadataMismatch.first}与输入不一致：解析值=${metadataMismatch.second.first}，预期值=${metadataMismatch.second.second}"
}
