package com.sultansgame.modmanager.split

import com.sultansgame.modmanager.model.ApkInspection

data class LoaderSplitArtifact(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val inspection: ApkInspection,
)

interface SplitArtifactFactory {
    fun build(request: LoaderSplitRequest): LoaderSplitResult
}

data class LoaderSplitRequest(
    val targetApplicationId: String,
    val loaderTemplateSha256: String,
    val target: ApkInspection,
    val templateOutputPath: String,
)

sealed interface LoaderSplitResult {
    data class Built(
        val artifact: LoaderSplitArtifact,
        val splitName: String,
        val verificationSummary: List<String>,
    ) : LoaderSplitResult

    data class Unavailable(val reason: String) : LoaderSplitResult
}

class UnavailableSplitArtifactFactory : SplitArtifactFactory {
    override fun build(request: LoaderSplitRequest): LoaderSplitResult =
        LoaderSplitResult.Unavailable(
            "尚未提供经验证的 loader split 模板或设备端 v1+v2 签名引擎；未生成 APK。",
        )
}
