package com.sultansgame.modmanager.platform.workshop

import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.model.SULTANS_GAME_APP_ID
import com.sultansgame.modmanager.model.WorkshopAccessMode
import com.sultansgame.modmanager.model.WorkshopAvailability
import com.sultansgame.modmanager.model.WorkshopItem
import com.sultansgame.modmanager.model.WorkshopSearchPage
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamPublishedFileClient
import top.apricityx.workshop.steam.protocol.SteamPublishedFileQuery
import top.apricityx.workshop.steam.protocol.newDefaultOkHttpClient

class SultanWorkshopCatalog {
    private val client = newDefaultOkHttpClient()
    private val publishedFiles = SteamPublishedFileClient(
        directoryClient = SteamDirectoryClient(client),
        sessionFactory = { OkHttpSteamCmSession(client) },
    )

    suspend fun search(account: SteamAccountSession, query: String, page: Int): WorkshopSearchPage {
        require(query.isNotBlank()) { "请输入搜索关键词。" }
        val response = publishedFiles.queryFiles(
            account = account,
            query = SteamPublishedFileQuery(
                appId = SULTANS_GAME_APP_ID,
                searchText = query.trim(),
                page = page.coerceAtLeast(1),
            ),
        )
        val items = response.items.mapNotNull { item ->
            if (item.appId != SULTANS_GAME_APP_ID) return@mapNotNull null
            PublishedFileId.parse(item.publishedFileId.toString())?.let { id ->
                WorkshopItem(
                    appId = item.appId,
                    publishedFileId = id,
                    title = item.title.ifBlank { "Workshop 条目 $id" },
                    updatedAtEpochSeconds = item.timeUpdatedEpochSeconds,
                    fileUrl = null,
                    previewUrl = item.previewUrl.takeIf(String::isNotBlank),
                    declaredSizeBytes = item.fileSizeBytes.takeIf { it >= 0 },
                    availability = WorkshopAvailability.LoginRequired,
                    description = item.description,
                    authorName = item.creatorSteamId.toString(),
                )
            }
        }
        return WorkshopSearchPage(
            items = items,
            page = page.coerceAtLeast(1),
            hasNextPage = response.nextCursor != null || response.total > page * 30,
        )
    }

    fun accessMode(account: SteamAccountSession?): WorkshopAccessMode =
        if (account == null) WorkshopAccessMode.Anonymous else WorkshopAccessMode.Account
}
