package com.sultansgame.modmanager.merge

import kotlinx.serialization.Serializable

@Serializable
data class BaseIdCatalog(
    val profileId: String,
    val versionCode: Long,
    val catalogVersion: String,
    val cards: Set<String> = emptySet(),
    val tagCodes: Set<String> = emptySet(),
    val tagIds: Set<Int> = emptySet(),
    val tagNames: Set<String> = emptySet(),
    val over: Set<String> = emptySet(),
    val rite: Set<String> = emptySet(),
    val event: Set<String> = emptySet(),
    val loot: Set<String> = emptySet(),
    val riteTemplate: Set<String> = emptySet(),
    val riteTemplateMappings: Set<String> = emptySet(),
)

data class CatalogSelection(
    val catalog: BaseIdCatalog,
    val exactVersion: Boolean,
) {
    val warning: String?
        get() = if (exactVersion) null else
            "当前游戏版本没有精确 ID 表，正在使用旧版本 ID 表进行合并推测。新增 ID 可能与游戏本体冲突。"
}

class BaseIdCatalogSelector(private val catalogs: List<BaseIdCatalog>) {
    fun select(profileId: String, versionCode: Long): CatalogSelection? {
        val compatible = catalogs.filter { it.profileId == profileId }
        val exact = compatible.firstOrNull { it.versionCode == versionCode }
        if (exact != null) return CatalogSelection(exact, exactVersion = true)
        return compatible.filter { it.versionCode <= versionCode }
            .maxByOrNull { it.versionCode }
            ?.let { CatalogSelection(it, exactVersion = false) }
    }
}

@Serializable
data class MergePlan(
    val orderedCacheKeys: List<String>,
) {
    init {
        require(orderedCacheKeys.size >= 2) { "至少选择两个 Mod" }
        require(orderedCacheKeys.distinct().size == orderedCacheKeys.size) { "合并 Mod 不得重复" }
    }
}

data class MergeIdConflict(
    val entityType: String,
    val id: String,
    val modIndexes: List<Int>,
)

data class MergePreflight(
    val conflicts: List<MergeIdConflict>,
    val remappedEntries: Int,
    val catalogWarning: String?,
)

data class MergeRequest(
    val plan: MergePlan,
    val catalog: CatalogSelection,
    val displayName: String,
)

sealed interface MergeResult {
    data class Success(
        val cacheKey: String,
        val displayName: String,
        val stoppedOriginalSync: Boolean,
    ) : MergeResult

    data class Failure(val reason: String) : MergeResult
}

fun BaseIdCatalog.contains(entityType: String, id: String): Boolean = when (entityType) {
    "cards" -> id in cards
    "tag" -> id in tagCodes
    "tag_id" -> id.toIntOrNull() in tagIds
    "over" -> id in over
    "rite" -> id in rite
    "event" -> id in event
    "loot" -> id in loot
    "rite_template" -> id in riteTemplate
    "rite_template_mappings" -> id in riteTemplateMappings
    else -> false
}
