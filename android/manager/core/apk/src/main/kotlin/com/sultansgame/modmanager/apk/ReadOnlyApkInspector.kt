package com.sultansgame.modmanager.apk

import com.sultansgame.modmanager.model.ApkInspection
import com.sultansgame.modmanager.model.Compatibility
import com.sultansgame.modmanager.model.CompatibilityReport
import com.sultansgame.modmanager.model.GameProfile
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

private const val MAXIMUM_APK_ENTRIES = 100_000

class ReadOnlyApkInspector {
    fun inspect(sourceLabel: String, sizeBytes: Long, source: () -> InputStream): ApkInspection {
        val warnings = mutableListOf<String>()
        val abis = linkedSetOf<String>()
        var entryCount = 0
        var manifestPresent = false

        source().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > MAXIMUM_APK_ENTRIES) {
                        warnings += "APK 条目数超过安全检查上限"
                        break
                    }
                    if (entry.name == "AndroidManifest.xml") manifestPresent = true
                    parseAbi(entry.name)?.let(abis::add)
                    if (entry.name.contains("..") || entry.name.startsWith("/") || entry.name.startsWith("\\")) {
                        warnings += "ZIP 包含不安全条目：${entry.name}"
                    }
                }
            }
        }
        if (!manifestPresent) warnings += "未找到 AndroidManifest.xml"
        if (abis.isEmpty()) warnings += "未找到原生 ABI 条目"

        return ApkInspection(
            sourceLabel = sourceLabel,
            packageName = null,
            versionCode = null,
            versionName = null,
            splitName = null,
            supportedAbis = abis,
            signerDigestsSha256 = emptySet(),
            entryCount = entryCount,
            sizeBytes = sizeBytes,
            warnings = warnings.distinct(),
        )
    }

    fun evaluate(inspection: ApkInspection, profile: GameProfile?): CompatibilityReport {
        if (profile == null) {
            return CompatibilityReport(
                Compatibility.Unverified,
                listOf("尚未匹配到已知的发布 game profile；此 APK 只能作为候选输入。"),
            )
        }
        val reasons = buildList {
            if (inspection.packageName !in profile.packageNames) add("包名不在目标配置中")
            if (profile.requiredAbi !in inspection.supportedAbis) add("APK 不包含 ${profile.requiredAbi}")
            if (inspection.signerDigestsSha256.isEmpty()) add("当前只读检查未提供签名证书摘要")
            else if (inspection.signerDigestsSha256.none(profile.signingDigestsSha256::contains)) add("签名证书不匹配")
            addAll(inspection.warnings)
        }
        return CompatibilityReport(
            if (reasons.isEmpty()) Compatibility.Candidate else Compatibility.Unsupported,
            reasons,
        )
    }

    fun sha256(source: () -> InputStream): String = source().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun parseAbi(path: String): String? {
        val prefix = "lib/"
        if (!path.startsWith(prefix)) return null
        val separator = path.indexOf('/', prefix.length)
        if (separator <= prefix.length || !path.endsWith(".so")) return null
        return path.substring(prefix.length, separator)
    }
}
