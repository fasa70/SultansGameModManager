package com.sultansgame.modmanager.platform.patch

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File

sealed interface ApkSigningResult {
    data class Signed(
        val output: File,
        val verifiedV1: Boolean,
        val verifiedV2: Boolean,
        val verifiedV3: Boolean,
    ) : ApkSigningResult

    data class Failed(val reason: String) : ApkSigningResult
}

interface ApkSigningEngine {
    fun sign(input: File, output: File, identity: DeviceSigningIdentity): ApkSigningResult
}

class AndroidKeystoreApkSigner : ApkSigningEngine {
    override fun sign(input: File, output: File, identity: DeviceSigningIdentity): ApkSigningResult = runCatching {
        require(input.isFile) { "签名输入 APK 不存在" }
        require(!output.exists()) { "签名输出 APK 已存在" }
        output.parentFile?.mkdirs()
        val payload = input.payloadSnapshot()
        val signer = ApkSigner.SignerConfig.Builder(
            "device",
            identity.privateKey,
            identity.certificateChain.map { certificate -> certificate as java.security.cert.X509Certificate },
        ).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .setMinSdkVersion(MIN_SDK)
            .build()
            .sign()
        check(payload == output.payloadSnapshot()) { "重签改变了 APK payload" }
        val result = ApkVerifier.Builder(output).setMinCheckedPlatformVersion(MIN_SDK).build().verify()
        check(result.isVerified) { "APK 签名校验失败：${result.errors.joinToString()}" }
        val v1 = result.v1SchemeSigners.isNotEmpty()
        val v2 = result.v2SchemeSigners.isNotEmpty()
        val v3 = result.v3SchemeSigners.isNotEmpty()
        check(v1 && v2) { "APK 未同时包含 v1 和 v2 签名" }
        ApkSigningResult.Signed(output, v1, v2, v3)
    }.getOrElse { error ->
        output.delete()
        ApkSigningResult.Failed(error.message ?: "设备端 APK 签名失败")
    }

    private companion object {
        const val MIN_SDK = 23
    }
}
