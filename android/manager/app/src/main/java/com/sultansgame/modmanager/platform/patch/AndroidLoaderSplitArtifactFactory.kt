package com.sultansgame.modmanager.platform.patch

import android.content.Context
import com.sultansgame.modmanager.apk.ReadOnlyApkInspector
import com.sultansgame.modmanager.split.LoaderSplitArtifact
import com.sultansgame.modmanager.split.LoaderSplitRequest
import com.sultansgame.modmanager.split.LoaderSplitResult
import com.sultansgame.modmanager.split.SplitArtifactFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal class AndroidLoaderSplitArtifactFactory(
    private val context: Context,
    private val expectedNativeSha256: String,
) : SplitArtifactFactory {
    private val archiveInspector = AndroidApkArchiveInspector(context)
    private val zipInspector = ReadOnlyApkInspector()

    override fun build(request: LoaderSplitRequest): LoaderSplitResult = runCatching {
        require(request.targetApplicationId == GAME_PACKAGE) { "loader split 目标包名不匹配" }
        require(request.loaderSplitName == SPLIT_NAME) { "loader split 名称与冻结模板不匹配" }
        require(request.target.versionCode != null) { "目标 APK 缺少版本号" }
        val destination = File(request.templateOutputPath).canonicalFile
        val stagingRoot = File(context.filesDir, "patch-staging").canonicalFile
        require(destination.parentFile?.name == "template") { "loader split 模板暂存路径无效" }
        require(destination.parentFile?.canonicalFile?.parentFile?.parentFile == stagingRoot) { "loader split 模板暂存路径无效" }
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, ".${destination.name}.${java.util.UUID.randomUUID()}.partial")
        try {
            context.assets.open(TEMPLATE_ASSET).use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            val templateDigest = zipInspector.sha256 { FileInputStream(partial) }
            val parsed = archiveInspector.inspect(partial, TEMPLATE_ASSET)
            require(parsed.packageName == null || parsed.packageName == GAME_PACKAGE) { "loader split 包名不匹配" }
            require(parsed.versionCode == null || parsed.versionCode == request.target.versionCode) {
                "loader split 版本与 base 不一致"
            }
            require(parsed.splitName == null || parsed.splitName == request.loaderSplitName) { "loader split 名称不匹配" }
            require(parsed.signerDigestsSha256.isEmpty()) { "loader split 模板必须未签名" }
            val inspection = parsed.copy(
                packageName = GAME_PACKAGE,
                versionCode = request.target.versionCode,
                versionName = request.target.versionName,
                splitName = request.loaderSplitName,
            )
            val nativeDigest = nativeDigest(partial)
            require(nativeDigest == expectedNativeSha256) { "loader split native 摘要不匹配" }
            if (!partial.renameTo(destination)) {
                throw java.io.IOException("无法提交 loader split 模板")
            }
            LoaderSplitResult.Built(
                artifact = LoaderSplitArtifact(
                    path = destination.absolutePath,
                    sha256 = templateDigest,
                    sizeBytes = destination.length(),
                    inspection = inspection,
                ),
                splitName = request.loaderSplitName,
                verificationSummary = listOf(
                    "内嵌 loader 模板已复制",
                    "同包名 split=${request.loaderSplitName}",
                    "native 摘要已验证",
                ),
            )
        } finally {
            partial.delete()
        }
    }.getOrElse { error ->
        LoaderSplitResult.Unavailable(error.message ?: "无法构建 loader split")
    }

    private fun nativeDigest(file: File): String {
        val temporary = File.createTempFile("modloader-native", ".bin", context.cacheDir)
        try {
            java.util.zip.ZipFile(file).use { archive ->
                val entry = requireNotNull(archive.getEntry(NATIVE_ASSET)) { "loader split 缺少 native asset" }
                require(entry.method == java.util.zip.ZipEntry.STORED) { "loader split native asset 必须未压缩" }
                archive.getInputStream(entry).use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            return zipInspector.sha256 { FileInputStream(temporary) }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val GAME_PACKAGE = "com.gametree.sultan.pd"
        const val SPLIT_NAME = "modloader"
        const val TEMPLATE_ASSET = "release/modloader-template-10005.apk"
        const val NATIVE_ASSET = "assets/modloader/arm64-v8a/modloader.bin"
    }
}
