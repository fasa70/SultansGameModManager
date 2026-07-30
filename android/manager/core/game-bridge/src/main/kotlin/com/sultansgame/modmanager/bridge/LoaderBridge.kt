package com.sultansgame.modmanager.bridge

import com.sultansgame.modmanager.model.DeploymentSnapshot
import com.sultansgame.modmanager.model.GameModStorageStatus
import com.sultansgame.modmanager.model.LoaderFailure
import com.sultansgame.modmanager.model.LoaderRuntimeState
import com.sultansgame.modmanager.model.LoaderStatus
import com.sultansgame.modmanager.model.ModStorageAvailability
import com.sultansgame.modmanager.model.ModStorageFailureCode
import com.sultansgame.modmanager.model.ModStorageSyncResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class ApplyRequest(
    val snapshot: DeploymentSnapshot,
)

sealed interface ApplyResult {
    data class Applied(val result: ModStorageSyncResult) : ApplyResult
    data class Rejected(val status: GameModStorageStatus) : ApplyResult
}

interface LoaderBridge {
    fun runtimeStatus(): Flow<LoaderStatus>
    suspend fun storageStatus(): GameModStorageStatus
    suspend fun requestApply(request: ApplyRequest): ApplyResult
    suspend fun stopGameForSync(): GameModStorageStatus
    suspend fun revokeStorageAuthorization(): GameModStorageStatus
}

class UnavailableLoaderBridge : LoaderBridge {
    private val unavailableStatus = LoaderStatus(
        state = LoaderRuntimeState.NotStarted,
        failure = LoaderFailure.None,
        rawStateCode = LoaderRuntimeState.NotStarted.nativeCode,
        rawFailureCode = LoaderFailure.None.nativeCode,
    )

    private val storageUnavailable = GameModStorageStatus(
        availability = ModStorageAvailability.ProviderMissing,
        failureCode = ModStorageFailureCode.ProviderMissing,
        reason = "游戏内 ModStorageProvider 尚未安装；未写入游戏目录。",
    )

    override fun runtimeStatus(): Flow<LoaderStatus> = flowOf(unavailableStatus)

    override suspend fun storageStatus(): GameModStorageStatus = storageUnavailable

    override suspend fun requestApply(request: ApplyRequest): ApplyResult =
        ApplyResult.Rejected(storageUnavailable)

    override suspend fun stopGameForSync(): GameModStorageStatus = storageUnavailable

    override suspend fun revokeStorageAuthorization(): GameModStorageStatus = storageUnavailable
}
