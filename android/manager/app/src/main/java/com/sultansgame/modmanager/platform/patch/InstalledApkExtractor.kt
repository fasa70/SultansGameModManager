package com.sultansgame.modmanager.platform.patch

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.sultansgame.modmanager.model.ApkInspection
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class ExtractedApk(
    val file: File,
    val inspection: ApkInspection,
    val sha256: String,
)

data class ExtractedApkSet(
    val transactionId: String,
    val root: File,
    val base: ExtractedApk,
    val splits: List<ExtractedApk>,
) {
    val supportedAbis: Set<String>
        get() = (listOf(base) + splits).flatMapTo(linkedSetOf()) { it.inspection.supportedAbis }
}

class InstalledApkExtractor(private val context: Context) {
    private val archiveInspector = AndroidApkArchiveInspector(context)

    fun extract(packageName: String): ExtractedApkSet {
        val packageInfo = packageInfoFor(packageName)
        val applicationInfo = requireNotNull(packageInfo.applicationInfo) { "游戏未提供应用信息" }
        val transactionId = UUID.randomUUID().toString()
        val root = File(context.filesDir, "patch-staging/$transactionId/input").apply { mkdirs() }
        try {
            val base = copyApk(
                requireNotNull(applicationInfo.sourceDir) { "游戏未提供 base APK 路径" },
                File(root, "base.apk"),
            )
            val sourceSplits = applicationInfo.splitSourceDirs.orEmpty()
            val declaredSplitNames = packageInfo.splitNames.orEmpty().toList()
            require(sourceSplits.size == declaredSplitNames.size) { "游戏 split 元数据不完整" }
            require(declaredSplitNames.all { !it.isNullOrBlank() }) { "游戏 split 元数据包含空名称" }
            require(declaredSplitNames.distinct().size == declaredSplitNames.size) { "游戏 split 元数据包含重复名称" }
            val splits = sourceSplits.mapIndexed { index, source ->
                val declaredName = declaredSplitNames[index]
                val copied = copyApk(source, File(root, "split-$index.apk"))
                copied.copy(
                    // For an APK already installed by Android, PackageInfo is authoritative for split identity.
                    // Some older loader APKs do not expose their split attribute through archive inspection.
                    inspection = copied.inspection.copy(splitName = declaredName),
                )
            }
            return requireCompletePackageSet(transactionId, root, base, splits)
        } catch (error: Throwable) {
            root.parentFile?.deleteRecursively()
            throw error
        }
    }

    fun importSingle(source: InputStream, sourceLabel: String): ExtractedApkSet {
        val transactionId = UUID.randomUUID().toString()
        val root = File(context.filesDir, "patch-staging/$transactionId/input").apply { mkdirs() }
        try {
            val destination = File(root, "selected.apk")
            source.use { input -> copy(input, destination) }
            val artifact = inspect(destination, sourceLabel)
            return requireCompletePackageSet(transactionId, root, artifact, emptyList())
        } catch (error: Throwable) {
            root.parentFile?.deleteRecursively()
            throw error
        }
    }

    fun importApkSet(source: InputStream, sourceLabel: String): ExtractedApkSet {
        val transactionId = UUID.randomUUID().toString()
        val root = File(context.filesDir, "patch-staging/$transactionId/input").apply { mkdirs() }
        try {
            extractApks(source, root)
            val artifacts = root.listFiles().orEmpty().filter { it.isFile() }.sortedBy { it.name }
                .map { file -> inspect(file, "$sourceLabel:${file.name}") }
            val base = artifacts.singleOrNull { it.inspection.splitName == null }
                ?: throw IllegalArgumentException("APKS 必须包含唯一 base APK")
            return requireCompletePackageSet(transactionId, root, base, artifacts - base)
        } catch (error: Throwable) {
            root.parentFile?.deleteRecursively()
            throw error
        }
    }

    private fun extractApks(source: InputStream, root: File) {
        var count = 0
        ZipInputStream(source).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.isDirectory) continue
                if (!entry.name.endsWith(".apk", ignoreCase = true)) continue
                require(++count <= MAX_APKS_ARTIFACTS) { "APKS 包含过多 APK" }
                val name = entry.name.substringAfterLast('/')
                require(name.matches(Regex("[A-Za-z0-9_.-]+\\.apk", RegexOption.IGNORE_CASE))) { "APKS 包含不安全文件名" }
                val target = File(root, "$count-$name")
                copy(archive, target)
                archive.closeEntry()
            }
        }
        require(count > 0) { "APKS 未包含 APK" }
    }

    private fun requireCompletePackageSet(
        transactionId: String,
        root: File,
        base: ExtractedApk,
        splits: List<ExtractedApk>,
    ): ExtractedApkSet {
        require(base.inspection.packageName == GAME_PACKAGE) { "目标 APK 包名不匹配" }
        require(base.inspection.splitName == null) { "base APK 不能具有 splitName" }
        require(REQUIRED_ABI in (listOf(base) + splits).flatMap { it.inspection.supportedAbis }.toSet()) {
            "安装集合不包含 $REQUIRED_ABI"
        }
        require(base.inspection.signerDigestsSha256.isNotEmpty()) { "base APK 缺少有效签名" }
        val versionCode = base.inspection.versionCode ?: throw IllegalArgumentException("base APK 缺少版本号")
        val splitNames = mutableSetOf<String>()
        splits.forEach { split ->
            require(split.inspection.packageName == GAME_PACKAGE) { "split APK 包名不匹配" }
            require(split.inspection.versionCode == versionCode) { "split APK 版本与 base 不一致" }
            val splitName = split.inspection.splitName ?: throw IllegalArgumentException("安装集合包含第二个 base APK")
            require(splitNames.add(splitName)) { "安装集合包含重复 split：$splitName" }
            require(split.inspection.signerDigestsSha256 == base.inspection.signerDigestsSha256) {
                "split APK 签名与 base 不一致"
            }
        }
        return ExtractedApkSet(transactionId, root, base, splits.sortedBy { it.inspection.splitName })
    }

    private fun copyApk(sourcePath: String, destination: File): ExtractedApk {
        FileInputStream(sourcePath).use { input -> copy(input, destination) }
        return inspect(destination, sourcePath)
    }

    private fun copy(input: InputStream, destination: File) {
        FileOutputStream(destination).use { output ->
            input.copyTo(output)
            output.fd.sync()
        }
    }

    private fun inspect(file: File, sourceLabel: String): ExtractedApk = ExtractedApk(
        file = file,
        inspection = archiveInspector.inspect(file, sourceLabel),
        sha256 = com.sultansgame.modmanager.apk.ReadOnlyApkInspector().sha256 { FileInputStream(file) },
    )

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
    private fun applicationInfoFor(packageName: String): ApplicationInfo =
        requireNotNull(packageInfoFor(packageName).applicationInfo) { "游戏未提供应用信息" }

    private companion object {
        const val GAME_PACKAGE = "com.gametree.sultan.pd"
        const val REQUIRED_ABI = "arm64-v8a"
        const val MAX_APKS_ARTIFACTS = 64
    }
}
