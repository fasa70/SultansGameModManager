package com.sultansgame.modmanager.bridge

import com.sultansgame.modmanager.model.GameModDirectoryEntry
import com.sultansgame.modmanager.model.GameModSyncAvailability
import com.sultansgame.modmanager.model.GameModSyncFailureCode
import com.sultansgame.modmanager.model.GameModSyncItem
import com.sultansgame.modmanager.model.GameModSyncStatus

interface LoaderBridge {
    suspend fun listMods(): GameModSyncStatus
    suspend fun syncMod(item: GameModSyncItem): GameModSyncStatus
    suspend fun removeManagedMod(cacheKey: String): GameModSyncStatus
}

class UnavailableLoaderBridge : LoaderBridge {
    private val unavailable = GameModSyncStatus(
        availability = GameModSyncAvailability.ProviderMissing,
        failureCode = GameModSyncFailureCode.ProviderMissing,
        reason = "游戏内 Mod 同步服务尚未安装；未修改游戏目录。",
    )

    override suspend fun listMods(): GameModSyncStatus = unavailable

    override suspend fun syncMod(item: GameModSyncItem): GameModSyncStatus = unavailable

    override suspend fun removeManagedMod(cacheKey: String): GameModSyncStatus = unavailable
}
