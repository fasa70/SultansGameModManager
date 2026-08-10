package com.sultansgame.modmanager.platform.patch

import com.sultansgame.modmanager.model.PatchArtifact

internal object PatchSplitSetPolicy {
    fun withoutExistingLoader(
        splits: List<ExtractedApk>,
        loaderSplitName: String,
    ): List<ExtractedApk> {
        require(loaderSplitName.isNotBlank()) { "loader split 名称不能为空" }
        val loaders = splits.filter { it.inspection.splitName == loaderSplitName }
        require(loaders.size <= 1) { "安装集合包含重复 loader split：$loaderSplitName" }
        return splits.filterNot { it.inspection.splitName == loaderSplitName }
    }

    fun expectedSplitNames(
        splits: List<PatchArtifact>,
        loaderSplitName: String,
    ): List<String> {
        require(loaderSplitName.isNotBlank()) { "loader split 名称不能为空" }
        val names = splits.map { requireNotNull(it.inspection.splitName) { "split APK 缺少 splitName" } }
        require(names.distinct().size == names.size) { "最终安装集合包含重复 split" }
        require(names.count { it == loaderSplitName } == 1) { "最终安装集合必须包含唯一 loader split" }
        return names.sorted()
    }
}
