package com.sultansgame.modmanager

import com.sultansgame.modmanager.model.CachedMod
import com.sultansgame.modmanager.model.DeploymentEntry
import com.sultansgame.modmanager.model.DeviceSigningKeyState
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.GameModStorageStatus
import com.sultansgame.modmanager.model.LoaderStatus
import com.sultansgame.modmanager.model.PatchConfirmation
import com.sultansgame.modmanager.model.PatchInputClassification
import com.sultansgame.modmanager.model.PatchSource
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
    val workshopBrowse: WorkshopBrowseUiState = WorkshopBrowseUiState(),
    val downloadTasks: List<DownloadTask> = emptyList(),
    val steamAuthState: SteamAuthState = SteamAuthState.SignedOut,
    val deviceSigningKeyState: DeviceSigningKeyState? = null,
    val patch: PatchUiState = PatchUiState.ChooseSource,
    val preparedPatchRecovery: PreparedPatchRecovery? = null,
    val apksExport: ApksExportUiState = ApksExportUiState.Idle,
    val noticeAccepted: Boolean? = null,
    val feedback: FeedbackMessage? = null,
)

data class PreparedPatchRecovery(
    val transactionId: String,
    val summary: String,
)

data class PatchInputUiModel(
    val source: PatchSource,
    val sourceLabel: String,
    val versionLabel: String,
    val splitCount: Int,
    val signerSummary: String,
    val classification: PatchInputClassification,
)

sealed interface PatchUiState {
    data object ChooseSource : PatchUiState
    data class Importing(val label: String) : PatchUiState
    data class Review(
        val input: PatchInputUiModel,
        val confirmation: PatchConfirmation = PatchConfirmation(),
    ) : PatchUiState
    data class Preparing(val input: PatchInputUiModel) : PatchUiState
    data class AwaitingOriginalUninstall(
        val transactionId: String,
        val gameState: GameProbeResult?,
        val summary: String,
    ) : PatchUiState
    data class ReadyToInstall(
        val transactionId: String,
        val summary: String,
    ) : PatchUiState
    data class SubmittingInstall(val transactionId: String) : PatchUiState
    data class AwaitingInstallPermission(
        val transactionId: String?,
        val input: PatchInputUiModel?,
        val confirmation: PatchConfirmation?,
    ) : PatchUiState
    data class AwaitingSystemInstall(val transactionId: String) : PatchUiState
    data class Completed(val transactionId: String) : PatchUiState
    data class Failed(val reason: String, val transactionId: String? = null) : PatchUiState
}

sealed interface ApksExportUiState {
    data object Idle : ApksExportUiState

    data class SelectingDestination(val transactionId: String) : ApksExportUiState

    data class Validating(val transactionId: String) : ApksExportUiState

    data class Writing(
        val transactionId: String,
        val artifactName: String,
        val completedArtifacts: Int,
        val artifactCount: Int,
        val writtenBytes: Long,
        val totalBytes: Long,
    ) : ApksExportUiState
}

data class FeedbackMessage(
    val text: String,
    val isError: Boolean = false,
)

data class WorkshopBrowseUiState(
    val query: com.sultansgame.modmanager.model.WorkshopBrowseQuery = com.sultansgame.modmanager.model.WorkshopBrowseQuery(),
    val items: List<WorkshopItem> = emptyList(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val sectionOptions: List<com.sultansgame.modmanager.model.WorkshopBrowseSectionOption> = emptyList(),
    val sortOptions: List<com.sultansgame.modmanager.model.WorkshopBrowseSortOption> = emptyList(),
    val periodOptions: List<com.sultansgame.modmanager.model.WorkshopBrowsePeriodOption> = emptyList(),
    val tagGroups: List<com.sultansgame.modmanager.model.WorkshopBrowseTagGroup> = emptyList(),
    val supportsIncompatibleFilter: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface WorkshopUiState {
    data object Idle : WorkshopUiState
    data object Loading : WorkshopUiState
    data class Item(val item: WorkshopItem) : WorkshopUiState
    data class Error(val reason: String) : WorkshopUiState
}
