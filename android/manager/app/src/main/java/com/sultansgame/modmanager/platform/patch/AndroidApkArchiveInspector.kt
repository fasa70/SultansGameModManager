package com.sultansgame.modmanager.platform.patch

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import com.sultansgame.modmanager.apk.ReadOnlyApkInspector
import com.sultansgame.modmanager.model.ApkInspection
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal class AndroidApkArchiveInspector(private val context: Context) {
    private val zipInspector = ReadOnlyApkInspector()

    fun inspect(file: File, sourceLabel: String): ApkInspection {
        require(file.isFile) { "APK 文件不存在" }
        val zipInspection = zipInspector.inspect(sourceLabel, file.length()) { FileInputStream(file) }
        val packageInfo = packageInfo(file)
        val warnings = zipInspection.warnings.toMutableList()
        val signerDigests = runCatching {
            ApkVerifier.Builder(file).build().verify().signerCertificates.map(::certificateDigest).toSet()
        }.getOrElse {
            warnings += "无法读取 APK 签名：${it.message ?: "未知错误"}"
            emptySet()
        }
        if (packageInfo == null) {
            warnings += "无法读取 APK 包元数据"
        }
        return zipInspection.copy(
            packageName = packageInfo?.packageName,
            versionCode = packageInfo?.versionCodeCompat(),
            versionName = packageInfo?.versionName,
            splitName = packageInfo?.splitNames?.singleOrNull(),
            signerDigestsSha256 = signerDigests,
            warnings = warnings.distinct(),
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(file: File): PackageInfo? {
        val flags = PackageManager.GET_META_DATA or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)?.also { info ->
            info.applicationInfo?.apply {
                sourceDir = file.absolutePath
                publicSourceDir = file.absolutePath
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun certificateDigest(certificate: java.security.cert.X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
}
