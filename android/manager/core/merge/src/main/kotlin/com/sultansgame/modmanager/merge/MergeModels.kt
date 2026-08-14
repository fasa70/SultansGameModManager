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
    val warning: String? = null,
)

class BaseIdCatalogSelector(private val catalogs: List<BaseIdCatalog>) {
    fun select(profileId: String, versionCode: Long): CatalogSelection? =
        catalogs.firstOrNull { it.profileId == profileId && it.versionCode == versionCode }
            ?.let { CatalogSelection(it, exactVersion = true) }
            ?: catalogs.firstOrNull { it.profileId == profileId }
                ?.let {
                    CatalogSelection(
                        it,
                        exactVersion = false,
                        warning = "游戏版本与 ID Catalog 不匹配，将使用可用 Catalog 继续尝试。",
                    )
                }
            ?: catalogs.singleOrNull()?.let {
                CatalogSelection(
                    it,
                    exactVersion = false,
                    warning = "无法确认游戏版本对应的 ID Catalog，将使用可用 Catalog 继续尝试。",
                )
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

data class MergeWarning(
    val code: String,
    val message: String,
    val entityType: String? = null,
    val count: Int? = null,
)

data class MergePreflight(
    val conflicts: List<MergeIdConflict>,
    val warnings: List<MergeWarning>,
    val remappedEntries: Int,
    val catalogWarning: String?,
    val bestEffort: Boolean,
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
    "tag", "tag_code" -> id in tagCodes
    "tag_id" -> id.toIntOrNull() in tagIds
    "tag_name" -> id in tagNames
    "over" -> id in over
    "rite" -> id in rite
    "event" -> id in event
    "loot" -> id in loot
    "rite_template" -> id in riteTemplate
    "rite_template_mappings" -> id in riteTemplateMappings
    else -> false
}
