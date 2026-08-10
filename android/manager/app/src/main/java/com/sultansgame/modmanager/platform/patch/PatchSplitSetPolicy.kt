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

    fun validateReplacement(
        sourceSplitNames: List<String>,
        expectedSplitNames: List<String>,
        loaderSplitName: String,
    ) {
        require(loaderSplitName.isNotBlank()) { "loader split 名称不能为空" }
        require(sourceSplitNames.none(String::isBlank)) { "源 split 集合包含空名称" }
        require(sourceSplitNames.distinct().size == sourceSplitNames.size) { "源 split 集合包含重复名称" }
        require(expectedSplitNames.none(String::isBlank)) { "最终 split 集合包含空名称" }
        require(expectedSplitNames.distinct().size == expectedSplitNames.size) { "最终 split 集合包含重复名称" }
        require(expectedSplitNames.count { it == loaderSplitName } == 1) { "最终安装集合必须包含唯一 loader split" }
        require(sourceSplitNames.filterNot { it == loaderSplitName }.toSet() ==
            expectedSplitNames.filterNot { it == loaderSplitName }.toSet()) {
            "替换 loader 时不能增删原始游戏 split"
        }
        require(sourceSplitNames.count { it == loaderSplitName } <= 1) { "源安装集合包含重复 loader split" }
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
