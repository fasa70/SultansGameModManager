package com.sultansgame.modmanager.bridge

import com.sultansgame.modmanager.model.GameModDirectoryEntry
import com.sultansgame.modmanager.model.GameModSyncAvailability
import com.sultansgame.modmanager.model.GameModSyncFailureCode
import com.sultansgame.modmanager.model.GameModSyncItem
import com.sultansgame.modmanager.model.GameModSyncStatus
import com.sultansgame.modmanager.model.GameModSyncTransferProgress
import com.sultansgame.modmanager.model.GameSaveAvailability
import com.sultansgame.modmanager.model.GameSaveFailureCode
import com.sultansgame.modmanager.model.GameSaveStatus

interface LoaderBridge {
    suspend fun listMods(): GameModSyncStatus

    /**
     * Streams one mod to the game-side Provider.
     *
     * [onProgress] is invoked from the transfer thread as bytes are written into the pipe; it must
     * be cheap and non-blocking.
     */
    suspend fun syncMod(
        item: GameModSyncItem,
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): GameModSyncStatus

    suspend fun removeManagedMod(cacheKey: String): GameModSyncStatus
    suspend fun listSaveUsers(): GameSaveStatus
    suspend fun listSaveFiles(uid: String): GameSaveStatus
    suspend fun readSave(uid: String, fileName: String): GameSaveStatus
    suspend fun writeSave(uid: String, fileName: String, content: String): GameSaveStatus
}

class UnavailableLoaderBridge : LoaderBridge {
    private val unavailable = GameModSyncStatus(
        availability = GameModSyncAvailability.ProviderMissing,
        failureCode = GameModSyncFailureCode.ProviderMissing,
        reason = "游戏内 Mod 同步服务尚未安装；未修改游戏目录。",
    )

    private val saveUnavailable = GameSaveStatus(
        availability = GameSaveAvailability.ProviderMissing,
        failureCode = GameSaveFailureCode.ProviderMissing,
        reason = "游戏内存档服务尚未安装；请重新修补游戏以启用存档编辑。",
    )

    override suspend fun listMods(): GameModSyncStatus = unavailable

    override suspend fun syncMod(
        item: GameModSyncItem,
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)?,
    ): GameModSyncStatus = unavailable

    override suspend fun removeManagedMod(cacheKey: String): GameModSyncStatus = unavailable

    override suspend fun listSaveUsers(): GameSaveStatus = saveUnavailable

    override suspend fun listSaveFiles(uid: String): GameSaveStatus = saveUnavailable

    override suspend fun readSave(uid: String, fileName: String): GameSaveStatus = saveUnavailable

    override suspend fun writeSave(uid: String, fileName: String, content: String): GameSaveStatus = saveUnavailable
}
