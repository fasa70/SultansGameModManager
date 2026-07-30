package com.sultansgame.modmanager.platform.workshop

import com.sultansgame.modmanager.model.SULTANS_GAME_APP_ID
import com.sultansgame.modmanager.model.WorkshopBrowsePage
import com.sultansgame.modmanager.model.WorkshopBrowseQuery
import com.sultansgame.modmanager.model.WorkshopItem
import com.sultansgame.modmanager.workshop.PublicWorkshopBrowseItemSkeleton
import com.sultansgame.modmanager.workshop.PublicWorkshopBrowseParser
import com.sultansgame.modmanager.workshop.SteamPublicWorkshopProvider
import com.sultansgame.modmanager.model.WorkshopAccessMode
import com.sultansgame.modmanager.workshop.WorkshopLookupResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GPLv3 adaptation of Workshop-Native's public Community browse flow.
 *
 * Community HTML supplies browse filters and item order. Every item is then
 * resolved through the existing public metadata provider before it can expose
 * a download action, so page HTML is never trusted as an artifact source.
 */
class SteamCommunityWorkshopBrowser(
    private val client: OkHttpClient,
    private val metadataProvider: SteamPublicWorkshopProvider,
) {
    fun browse(query: WorkshopBrowseQuery): WorkshopBrowsePage {
        val normalizedQuery = query.normalized()
        val url = buildBrowseUrl(normalizedQuery)
        val html = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build(),
        ).execute().use { response ->
            check(response.isSuccessful) { "Steam 创意工坊页面请求失败：HTTP ${response.code}" }
            check(!isUnavailableRedirect(response.request.url.toString())) { "《苏丹的游戏》当前没有可用的 Steam 创意工坊页面。" }
            response.body.string()
        }

        val skeleton = PublicWorkshopBrowseParser.parse(
            appId = SULTANS_GAME_APP_ID.toInt(),
            query = normalizedQuery,
            html = html,
            baseUrl = url,
        )
        val items = skeleton.items.map(::resolveSkeleton)
        val totalCount = skeleton.totalCount ?: inferTotalCount(
            page = normalizedQuery.page,
            maxPage = skeleton.maxPage,
            itemCount = items.size,
            pageSize = normalizedQuery.pageSize,
        )

        return WorkshopBrowsePage(
            items = if (skeleton.isExplicitlyEmpty && totalCount == 0) emptyList() else items,
            totalCount = totalCount,
            page = normalizedQuery.page,
            hasMore = normalizedQuery.page < skeleton.maxPage || normalizedQuery.page * normalizedQuery.pageSize < totalCount,
            sectionOptions = skeleton.sectionOptions,
            sortOptions = skeleton.sortOptions,
            periodOptions = skeleton.periodOptions,
            tagGroups = skeleton.tagGroups,
            supportsIncompatibleFilter = skeleton.supportsIncompatibleFilter,
        )
    }

    fun buildBrowseUrl(query: WorkshopBrowseQuery): String = COMMUNITY_BROWSE_URL.toHttpUrl().newBuilder().apply {
        val normalized = query.normalized()
        addQueryParameter("appid", SULTANS_GAME_APP_ID.toString())
        addQueryParameter("l", "schinese")
        addQueryParameter("p", normalized.page.toString())
        addQueryParameter("numperpage", normalized.pageSize.toString())
        addQueryParameter("section", normalized.sectionKey)
        if (normalized.sortKey == WorkshopBrowseQuery.SORT_TREND) {
            addQueryParameter("actualsort", normalized.sortKey)
            addQueryParameter("browsesort", normalized.sortKey)
            addQueryParameter("days", normalized.periodDays.toString())
        } else {
            addQueryParameter("browsesort", normalized.sortKey)
        }
        normalized.searchText.takeIf(String::isNotBlank)?.let { addQueryParameter("searchtext", it) }
        normalized.requiredTags.forEach { addQueryParameter("requiredtags[]", it) }
        normalized.excludedTags.forEach { addQueryParameter("excludedtags[]", it) }
        if (normalized.showIncompatible) addQueryParameter("requiredflags[]", "incompatible")
        appendDateRange("created", normalized.createdDateRange.startEpochSeconds, normalized.createdDateRange.endEpochSeconds)
        appendDateRange("updated", normalized.updatedDateRange.startEpochSeconds, normalized.updatedDateRange.endEpochSeconds)
    }.build().toString()

    private fun okhttp3.HttpUrl.Builder.appendDateRange(prefix: String, start: Long, end: Long) {
        if (start > 0L) addQueryParameter("${prefix}_date_range_filter_start", start.toString())
        if (end > 0L) addQueryParameter("${prefix}_date_range_filter_end", end.toString())
    }

    private fun resolveSkeleton(skeleton: PublicWorkshopBrowseItemSkeleton): WorkshopItem {
        val id = com.sultansgame.modmanager.model.PublishedFileId.parse(skeleton.publishedFileId.toString())
            ?: error("Steam 返回了无效的 PublishedFileId。")
        val resolved = metadataProvider.getItem(SULTANS_GAME_APP_ID, id, WorkshopAccessMode.Anonymous)
        return when (resolved) {
            is WorkshopLookupResult.Available -> resolved.item.copy(
                title = resolved.item.title.ifBlank { skeleton.title.ifBlank { "Workshop 条目 $id" } },
                previewUrl = resolved.item.previewUrl ?: skeleton.previewUrl,
                authorName = resolved.item.authorName.ifBlank { skeleton.authorName },
                isDownloadInfoResolved = true,
            )
            is WorkshopLookupResult.Unavailable -> WorkshopItem(
                appId = SULTANS_GAME_APP_ID,
                publishedFileId = id,
                title = skeleton.title.ifBlank { "Workshop 条目 $id" },
                updatedAtEpochSeconds = null,
                fileUrl = null,
                previewUrl = skeleton.previewUrl,
                declaredSizeBytes = null,
                availability = com.sultansgame.modmanager.model.WorkshopAvailability.Unavailable,
                authorName = skeleton.authorName,
            )
        }
    }

    private fun inferTotalCount(page: Int, maxPage: Int, itemCount: Int, pageSize: Int): Int = when {
        itemCount == 0 -> 0
        maxPage > page -> maxPage * pageSize
        else -> (page - 1) * pageSize + itemCount
    }

    private fun isUnavailableRedirect(url: String): Boolean {
        val parsed = url.toHttpUrl()
        return parsed.host == "steamcommunity.com" &&
            parsed.encodedPath.trimEnd('/') == "/workshop" &&
            parsed.queryParameter("appid").isNullOrBlank()
    }

    private companion object {
        const val COMMUNITY_BROWSE_URL = "https://steamcommunity.com/workshop/browse/"
        const val USER_AGENT = "SultansGameModManager/0.1 (GPLv3; public Steam Workshop browser)"
    }
}
