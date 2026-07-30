package com.sultansgame.modmanager.platform.patch

import android.content.Context
import com.sultansgame.modmanager.apk.ReadOnlyApkInspector
import com.sultansgame.modmanager.model.ApkInspection
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
        require(request.target.versionCode != null) { "目标 APK 缺少版本号" }
        require(request.loaderTemplateSha256 == TEMPLATE_SHA256) { "loader split 模板摘要不匹配" }
        val destination = File(request.templateOutputPath).canonicalFile
        val stagingRoot = File(context.filesDir, "patch-staging").canonicalFile
        require(destination.parentFile?.name == "template") { "loader split 模板暂存路径无效" }
        require(destination.parentFile?.canonicalFile?.parentFile?.parentFile == stagingRoot) { "loader split 模板暂存路径无效" }
        destination.parentFile?.mkdirs()
        context.assets.open(TEMPLATE_ASSET).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        val templateDigest = zipInspector.sha256 { FileInputStream(destination) }
        require(templateDigest == TEMPLATE_SHA256) { "loader split 模板被篡改" }
        val parsed = archiveInspector.inspect(destination, TEMPLATE_ASSET)
        require(parsed.packageName == null || parsed.packageName == GAME_PACKAGE) { "loader split 包名不匹配" }
        require(parsed.versionCode == null || parsed.versionCode == request.target.versionCode) {
            "loader split 版本与 base 不一致"
        }
        require(parsed.splitName == null || parsed.splitName == SPLIT_NAME) { "loader split 名称不匹配" }
        require(parsed.signerDigestsSha256.isEmpty()) { "loader split 模板必须未签名" }
        val inspection = parsed.copy(
            packageName = GAME_PACKAGE,
            versionCode = request.target.versionCode,
            versionName = request.target.versionName,
            splitName = SPLIT_NAME,
        )
        val nativeDigest = nativeDigest(destination)
        require(nativeDigest == expectedNativeSha256) { "loader split native 摘要不匹配" }
        LoaderSplitResult.Built(
            artifact = LoaderSplitArtifact(
                path = destination.absolutePath,
                sha256 = templateDigest,
                sizeBytes = destination.length(),
                inspection = inspection,
            ),
            splitName = SPLIT_NAME,
            verificationSummary = listOf(
                "模板摘要已验证",
                "同包名 split=$SPLIT_NAME",
                "native 摘要已验证",
            ),
        )
    }.getOrElse { error ->
        LoaderSplitResult.Unavailable(error.message ?: "无法构建 loader split")
    }

    private fun nativeDigest(file: File): String {
        val temporary = File.createTempFile("modloader-native", ".bin", context.cacheDir)
        try {
            java.util.zip.ZipFile(file).use { archive ->
                val entry = requireNotNull(archive.getEntry(NATIVE_ASSET)) { "loader split 缺少 native asset" }
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
        const val TEMPLATE_SHA256 = "f173742e82b468ae88c6ec8d8af6350b445b131c320c5a1099bb05c05b3eb9b7"
    }
}
