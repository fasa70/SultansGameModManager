package com.sultansgame.modmanager.platform.game

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.sultansgame.modmanager.model.Compatibility
import com.sultansgame.modmanager.model.CompatibilityReport
import java.security.MessageDigest

data class InstalledGameArtifacts(
    val baseApkPath: String,
    val splitApkPaths: List<String>,
)

data class InstalledGameSnapshot(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signerDigestsSha256: Set<String>,
    val splitNames: Set<String>,
    val artifacts: InstalledGameArtifacts,
    val compatibility: CompatibilityReport,
)

sealed interface GameProbeResult {
    data class Found(val snapshot: InstalledGameSnapshot) : GameProbeResult
    data object NotInstalled : GameProbeResult
    data class Failed(val reason: String) : GameProbeResult
}

class PackageManagerGameProbe(private val context: Context) {
    fun probe(): GameProbeResult = runCatching {
        val packageInfo = packageInfoFor(TARGET_PACKAGE)
        GameProbeResult.Found(
            InstalledGameSnapshot(
                packageName = packageInfo.packageName,
                versionCode = packageInfo.versionCodeCompat(),
                versionName = packageInfo.versionName,
                signerDigestsSha256 = packageInfo.signerDigests(),
                splitNames = packageInfo.splitNames.orEmpty().toSet(),
                artifacts = InstalledGameArtifacts(
                    baseApkPath = requireNotNull(packageInfo.applicationInfo?.sourceDir) { "游戏未提供 base APK 路径" },
                    splitApkPaths = packageInfo.applicationInfo?.splitSourceDirs.orEmpty().sorted(),
                ),
                compatibility = CompatibilityReport(
                    compatibility = Compatibility.Unverified,
                    reasons = listOf("仅检查已安装包信息；兼容性分类由 GameProfileRegistry 完成。"),
                ),
            ),
        )
    }.getOrElse { error ->
        if (error is PackageManager.NameNotFoundException) GameProbeResult.NotInstalled
        else GameProbeResult.Failed("无法读取已安装游戏信息。")
    }

    fun verifiesMigration(
        expectedVersionCode: Long,
        expectedCertificateSha256: String,
        expectedSplitNames: Set<String>,
    ): Boolean = (probe() as? GameProbeResult.Found)?.snapshot?.let { snapshot ->
        snapshot.packageName == TARGET_PACKAGE &&
            snapshot.versionCode == expectedVersionCode &&
            snapshot.signerDigestsSha256 == setOf(expectedCertificateSha256) &&
            snapshot.splitNames == expectedSplitNames
    } == true

    @Suppress("DEPRECATION")
    private fun packageInfoFor(packageName: String): PackageInfo = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        else -> context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.let { info ->
                if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            }.orEmpty()
        } else {
            signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }.toSet()
    }

    companion object {
        const val TARGET_PACKAGE = "com.gametree.sultan.pd"
    }
}
