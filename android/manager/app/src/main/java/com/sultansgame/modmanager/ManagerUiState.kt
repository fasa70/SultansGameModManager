package com.sultansgame.modmanager

import com.sultansgame.modmanager.model.CachedMod
import com.sultansgame.modmanager.model.DeploymentEntry
import com.sultansgame.modmanager.model.DeviceSigningKeyState
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.GameModStorageStatus
import com.sultansgame.modmanager.model.LoaderStatus
import com.sultansgame.modmanager.model.PatchConfirmation
import com.sultansgame.modmanager.model.PatchInputClassification
import com.sultansgame.modmanager.model.PatchStage
import com.sultansgame.modmanager.model.SteamAuthState
import com.sultansgame.modmanager.model.WorkshopItem
import com.sultansgame.modmanager.platform.game.GameProbeResult

data class ManagerUiState(
    val gameProbeResult: GameProbeResult? = null,
    val loaderStatus: LoaderStatus? = null,
    val gameModStorage: GameModStorageStatus? = null,
    val deploymentPlan: List<DeploymentEntry> = emptyList(),
    val deploymentInProgress: Boolean = false,
    val cachedMods: List<CachedMod> = emptyList(),
    val workshop: WorkshopUiState = WorkshopUiState.Idle,
    val workshopSearch: WorkshopSearchUiState = WorkshopSearchUiState.Idle,
    val downloadTasks: List<DownloadTask> = emptyList(),
    val steamAuthState: SteamAuthState = SteamAuthState.SignedOut,
    val deviceSigningKeyState: DeviceSigningKeyState? = null,
    val patchClassification: PatchInputClassification? = null,
    val patchConfirmation: PatchConfirmation = PatchConfirmation(),
    val patchStage: PatchStage = PatchStage.Idle,
    val patchStatus: String? = null,
    val patchInProgress: Boolean = false,
    val noticeAccepted: Boolean? = null,
    val feedback: FeedbackMessage? = null,
)

data class FeedbackMessage(
    val text: String,
    val isError: Boolean = false,
)

sealed interface WorkshopUiState {
    data object Idle : WorkshopUiState
    data object Loading : WorkshopUiState
    data class Item(val item: WorkshopItem) : WorkshopUiState
    data class Error(val reason: String) : WorkshopUiState
}

sealed interface WorkshopSearchUiState {
    data object Idle : WorkshopSearchUiState
    data object Loading : WorkshopSearchUiState
    data class Results(
        val items: List<WorkshopItem>,
        val page: Int,
        val hasNextPage: Boolean,
    ) : WorkshopSearchUiState
    data class Error(val reason: String) : WorkshopSearchUiState
}
