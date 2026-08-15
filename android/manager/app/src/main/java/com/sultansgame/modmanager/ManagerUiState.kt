package com.sultansgame.modmanager

import com.sultansgame.modmanager.model.CachedMod
import com.sultansgame.modmanager.model.DeviceSigningKeyState
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.GameModSyncItem
import com.sultansgame.modmanager.model.GameModSyncStatus
import com.sultansgame.modmanager.model.PatchConfirmation
import com.sultansgame.modmanager.model.PatchInputClassification
import com.sultansgame.modmanager.model.PatchSource
import com.sultansgame.modmanager.model.PendingGameModSyncOperation
import com.sultansgame.modmanager.model.SteamAuthState
import com.sultansgame.modmanager.model.WorkshopItem
import com.sultansgame.modmanager.platform.game.GameProbeResult
import com.sultansgame.modmanager.platform.saf.ExternalZipImportRequest
import com.sultansgame.modmanager.merge.CatalogSelection
import com.sultansgame.modmanager.merge.MergeIdConflict
import com.sultansgame.modmanager.merge.MergePreflight

data class ModExportUiState(
    val isOpen: Boolean = false,
    val selectedCacheKeys: List<String> = emptyList(),
    val settingsAction: ModExportAction? = null,
    val suggestedFileName: String = "sultans-game-mods.zip",
    val operation: ModExportOperation = ModExportOperation.Idle,
)

enum class ModExportAction {
    Share,
    SaveToLocal,
}

sealed interface ModExportOperation {
    data object Idle : ModExportOperation
    data class Compressing(
        val action: ModExportAction,
        val fileName: String,
        val completedFiles: Int,
        val totalFiles: Int,
        val writtenBytes: Long,
        val totalBytes: Long,
    ) : ModExportOperation
    data class SelectingDestination(val artifactId: String, val fileName: String) : ModExportOperation
    data class Writing(
        val artifactId: String,
        val fileName: String,
        val writtenBytes: Long,
        val totalBytes: Long,
    ) : ModExportOperation
    data class Sharing(val artifactId: String, val fileName: String) : ModExportOperation
}

data class MergeUiState(
    val isOpen: Boolean = false,
    val selectedCacheKeys: List<String> = emptyList(),
    val catalogSelection: CatalogSelection? = null,
    val catalogError: String? = null,
    val conflicts: List<MergeIdConflict> = emptyList(),
    val warnings: List<com.sultansgame.modmanager.merge.MergeWarning> = emptyList(),
    val preflight: MergePreflightState = MergePreflightState.Idle,
    val isRunning: Boolean = false,
    val progress: String? = null,
    val resultCacheKey: String? = null,
    val resultDisplayName: String = "合并 Mod - 自动生成",
    val awaitingSyncDecision: Boolean = false,
    val modeLabel: String = "无本体 JSON 模式",
)

sealed interface MergePreflightState {
    data object Idle : MergePreflightState
    data class Running(val selection: List<String>) : MergePreflightState
    data class Ready(val selection: List<String>, val result: MergePreflight) : MergePreflightState
    data class Failed(val selection: List<String>, val reason: String) : MergePreflightState
}


data class ManagerUiState(
    val gameProbeResult: GameProbeResult? = null,
    val gameModSync: GameModSyncStatus? = null,
    val gameModSyncItems: List<GameModSyncItem> = emptyList(),
    val pendingGameModSyncOperations: List<PendingGameModSyncOperation> = emptyList(),
    val gameModSyncInProgress: Boolean = false,
    val cachedModDeletionInProgress: Boolean = false,
    val cachedMods: List<CachedMod> = emptyList(),
    val merge: MergeUiState = MergeUiState(),
    val modExport: ModExportUiState = ModExportUiState(),
    val workshop: WorkshopUiState = WorkshopUiState.Idle,
    val workshopBrowse: WorkshopBrowseUiState = WorkshopBrowseUiState(),
    val downloadTasks: List<DownloadTask> = emptyList(),
    val steamAuthState: SteamAuthState = SteamAuthState.SignedOut,
    val deviceSigningKeyState: DeviceSigningKeyState? = null,
    val patch: PatchUiState = PatchUiState.ChooseSource,
    val preparedPatchRecovery: PreparedPatchRecovery? = null,
    val patchCleanup: PatchCleanupUiModel? = null,
    val patchCleanupInProgress: Boolean = false,
    val patchCleanupConfirmation: PatchCleanupUiModel? = null,
    val apksExport: ApksExportUiState = ApksExportUiState.Idle,
    val noticeAccepted: Boolean? = null,
    val autoUpdateCheckEnabled: Boolean? = null,
    val availableUpdate: AvailableUpdate? = null,
    val pendingExternalZip: ExternalZipImportRequest? = null,
    val pendingZipPassword: Boolean = false,
    val zipImportInProgress: Boolean = false,
    val feedback: FeedbackMessage? = null,
)

data class PreparedPatchRecovery(
    val transactionId: String,
    val summary: String,
)

data class PatchCleanupUiModel(
    val workspaceIds: Set<String>,
    val sizeBytes: Long,
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
        val installMode: com.sultansgame.modmanager.model.PatchInstallMode = com.sultansgame.modmanager.model.PatchInstallMode.FreshInstall,
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
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val error: String? = null,
)

sealed interface WorkshopUiState {
    data object Idle : WorkshopUiState
    data object Loading : WorkshopUiState
    data class Item(val item: WorkshopItem) : WorkshopUiState
    data class Error(val reason: String) : WorkshopUiState
}
