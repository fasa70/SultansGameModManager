package com.sultansgame.modmanager

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sultansgame.modmanager.bridge.ApplyRequest
import com.sultansgame.modmanager.bridge.ApplyResult
import com.sultansgame.modmanager.bridge.LoaderBridge
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.PatchConfirmation
import com.sultansgame.modmanager.model.PatchSource
import com.sultansgame.modmanager.platform.patch.AndroidApkArchiveInspector
import com.sultansgame.modmanager.platform.patch.ApksExporter
import com.sultansgame.modmanager.platform.patch.ApksExportProgress
import com.sultansgame.modmanager.platform.patch.AndroidKeystoreApkSigner
import com.sultansgame.modmanager.platform.patch.AndroidLoaderSplitArtifactFactory
import com.sultansgame.modmanager.platform.patch.DeviceSigningKeyStore
import com.sultansgame.modmanager.platform.patch.GameProfileRegistry
import com.sultansgame.modmanager.platform.patch.InstalledApkExtractor
import com.sultansgame.modmanager.platform.patch.PackageInstallerGateway
import com.sultansgame.modmanager.platform.patch.PatchWorkspaceCleanupResult
import com.sultansgame.modmanager.platform.patch.PatchWorkspaceCleanupSummary
import com.sultansgame.modmanager.platform.patch.PatchInstallResults
import com.sultansgame.modmanager.platform.patch.PatchOrchestrationResult
import com.sultansgame.modmanager.platform.patch.PatchOrchestrator
import com.sultansgame.modmanager.platform.patch.PatchTransactionStore
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.model.SULTANS_GAME_APP_ID
import com.sultansgame.modmanager.model.WorkshopAccessMode
import com.sultansgame.modmanager.model.WorkshopItem
import com.sultansgame.modmanager.platform.auth.SteamAuthProvider
import com.sultansgame.modmanager.platform.auth.SteamCmAuthProvider
import com.sultansgame.modmanager.platform.auth.SteamCredentials
import com.sultansgame.modmanager.platform.auth.steamAccountBindingHash
import com.sultansgame.modmanager.platform.game.AndroidModStorageLoaderBridge
import com.sultansgame.modmanager.platform.game.GameProbeResult
import com.sultansgame.modmanager.platform.game.PackageManagerGameProbe
import com.sultansgame.modmanager.platform.saf.ExternalZipInbox
import com.sultansgame.modmanager.platform.saf.ZipModImporter
import com.sultansgame.modmanager.platform.storage.AndroidPrivateModCache
import com.sultansgame.modmanager.platform.storage.CachedModDeletionResult
import com.sultansgame.modmanager.platform.storage.DeploymentPlanStore
import com.sultansgame.modmanager.platform.workshop.SteamPublicMetadataTransport
import com.sultansgame.modmanager.platform.workshop.SteamCommunityWorkshopDetailTransport
import com.sultansgame.modmanager.platform.workshop.SteamCommunityWorkshopBrowser
import com.sultansgame.modmanager.platform.workshop.WorkshopArtifactImporter
import com.sultansgame.modmanager.platform.workshop.WorkshopDownloadScheduler
import com.sultansgame.modmanager.platform.workshop.WorkshopTaskStore
import com.sultansgame.modmanager.workshop.SteamPublicWorkshopProvider
import com.sultansgame.modmanager.workshop.WorkshopLookupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface ManagerUiEvent {
    data class LaunchGameForModService(val intent: android.content.Intent) : ManagerUiEvent
    data class OpenGameUninstall(val transactionId: String) : ManagerUiEvent
    data class OpenUnknownSourcesSettings(val intent: android.content.Intent) : ManagerUiEvent
    data class ConfirmPackageInstall(val intent: android.content.Intent) : ManagerUiEvent
    data class CreateApksExport(val transactionId: String, val suggestedName: String) : ManagerUiEvent
}

class ManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val privateModCache = AndroidPrivateModCache(File(application.filesDir, "mod-cache"))
    private val deploymentPlan = DeploymentPlanStore(application)
    private val zipImporter = ZipModImporter(application, privateModCache)
    private val externalZipInbox = ExternalZipInbox(application)
    private val artifactImporter = WorkshopArtifactImporter(application, privateModCache, zipImporter)
    private val taskStore = WorkshopTaskStore(application)
    private val downloadScheduler = WorkshopDownloadScheduler(application)
    private val steamAuthProvider = SteamCmAuthProvider(application)
    private val steamAuth: SteamAuthProvider = steamAuthProvider
    private val workshopProvider = SteamPublicWorkshopProvider(
        transport = SteamPublicMetadataTransport(),
        detailTransport = SteamCommunityWorkshopDetailTransport(),
    )
    private val communityWorkshopBrowser = SteamCommunityWorkshopBrowser(
        client = top.apricityx.workshop.steam.protocol.newDefaultOkHttpClient(),
        metadataProvider = workshopProvider,
    )
    private val gameProbe = PackageManagerGameProbe(application)
    private val deviceSigningKeyStore = DeviceSigningKeyStore(application)
    private val archiveInspector = AndroidApkArchiveInspector(application)
    private val profileRegistry = GameProfileRegistry()
    private val apkExtractor = InstalledApkExtractor(application)
    private val transactions = PatchTransactionStore(application)
    private val packageInstaller = PackageInstallerGateway(application)
    private val orchestrator = PatchOrchestrator(
        keyStore = deviceSigningKeyStore,
        profileRegistry = profileRegistry,
        signer = AndroidKeystoreApkSigner(),
        installer = packageInstaller,
        transactions = transactions,
        archiveInspector = archiveInspector,
        gameProbe = gameProbe,
        splitFactoryForNativeDigest = { nativeDigest ->
            AndroidLoaderSplitArtifactFactory(application, nativeDigest)
        },
    )
    private val loaderBridge: LoaderBridge = AndroidModStorageLoaderBridge(application, File(application.filesDir, "mod-cache"))
    private val legalNotice = LegalNoticeRepository(application)

    private val mutableState = MutableStateFlow(ManagerUiState())
    val state: StateFlow<ManagerUiState> = mutableState.asStateFlow()
    private val uiEventChannel = Channel<ManagerUiEvent>(Channel.BUFFERED)
    val uiEvents = uiEventChannel.receiveAsFlow()
    private var selectedPatchInput: SelectedPatchInput? = null

    private data class SelectedPatchInput(
        val source: PatchSource,
        val extracted: com.sultansgame.modmanager.platform.patch.ExtractedApkSet,
        val uiModel: PatchInputUiModel,
    )

    private var workshopBrowseJob: Job? = null
    private var steamGuardSubmissionJob: Job? = null
    private var workshopBrowseGeneration = 0L
    private var gameModStorageRefreshJob: Job? = null
    private var gameModStorageRefreshGeneration = 0L

    init {
        privateModCache.recoverInterruptedImports()
        externalZipInbox.recoverInterruptedReceipts()
        val cachedMods = privateModCache.listCached()
        val pendingPatch = transactions.latestPreparedForRecovery()
        val cleanupCandidate = transactions.cleanupSummary(emptySet())
        mutableState.value = mutableState.value.copy(
            cachedMods = cachedMods,
            deploymentPlan = deploymentPlan.entries(cachedMods),
            downloadTasks = taskStore.tasks.value,
            deviceSigningKeyState = deviceSigningKeyStore.state(),
            preparedPatchRecovery = pendingPatch?.toRecoveryUiModel(),
            patchCleanup = cleanupCandidate?.toCleanupUiModel(),
        )
        refreshGame()
        viewModelScope.launch {
            loaderBridge.runtimeStatus().collect { loaderStatus ->
                mutableState.value = mutableState.value.copy(loaderStatus = loaderStatus)
            }
        }
        viewModelScope.launch {
            steamAuth.observeState().collect { authState ->
                mutableState.value = mutableState.value.copy(steamAuthState = authState)
            }
        }
        viewModelScope.launch {
            taskStore.ready.collect { ready ->
                if (!ready) return@collect
                taskStore.recoverInterruptedTasks()
                    .filter { it.stage in DOWNLOAD_STAGES_TO_RESCHEDULE && !it.pauseRequested }
                    .forEach(downloadScheduler::enqueue)
            }
        }
        viewModelScope.launch {
            taskStore.tasks.collect { tasks ->
                mutableState.value = mutableState.value.copy(downloadTasks = tasks)
            }
        }
        viewModelScope.launch {
            legalNotice.isCurrentNoticeAccepted.collect { accepted ->
                mutableState.value = mutableState.value.copy(noticeAccepted = accepted)
            }
        }
        viewModelScope.launch {
            PatchInstallResults.results.collect { intent ->
                handleInstallResult(intent)
            }
        }
    }

    fun refreshGameModStorage() {
        if (mutableState.value.deploymentInProgress || gameModStorageRefreshJob?.isActive == true) return
        val generation = ++gameModStorageRefreshGeneration
        gameModStorageRefreshJob = viewModelScope.launch {
            val storage = withContext(Dispatchers.IO) { loaderBridge.storageStatus() }
            if (generation == gameModStorageRefreshGeneration && !mutableState.value.deploymentInProgress) {
                mutableState.value = mutableState.value.copy(gameModStorage = storage)
            }
        }
    }

    fun setModEnabled(cacheKey: String, enabled: Boolean) {
        if (mutableState.value.deploymentInProgress || mutableState.value.cachedModDeletionInProgress) return
        deploymentPlan.setEnabled(cacheKey, enabled, mutableState.value.cachedMods)
        refreshDeploymentPlan()
    }

    fun moveMod(cacheKey: String, delta: Int) {
        if (mutableState.value.deploymentInProgress || mutableState.value.cachedModDeletionInProgress) return
        deploymentPlan.move(cacheKey, delta, mutableState.value.cachedMods)
        refreshDeploymentPlan()
    }

    fun deleteCachedMod(cacheKey: String) {
        if (mutableState.value.deploymentInProgress) {
            mutableState.value = mutableState.value.copy(
                feedback = FeedbackMessage("正在同步 Mod 到游戏，暂时不能删除。", isError = true),
            )
            return
        }
        if (mutableState.value.cachedModDeletionInProgress) return
        val cachedModsBeforeDeletion = mutableState.value.cachedMods
        val target = cachedModsBeforeDeletion.firstOrNull { it.cacheKey == cacheKey } ?: return
        mutableState.value = mutableState.value.copy(cachedModDeletionInProgress = true)
        viewModelScope.launch {
            try {
                if (mutableState.value.deploymentInProgress) {
                    mutableState.value = mutableState.value.copy(
                        feedback = FeedbackMessage("正在同步 Mod 到游戏，暂时不能删除。", isError = true),
                    )
                    return@launch
                }
                when (val result = withContext(Dispatchers.IO) { privateModCache.deleteCached(target.cacheKey) }) {
                    CachedModDeletionResult.Deleted,
                    CachedModDeletionResult.NotFound -> {
                        deploymentPlan.remove(target.cacheKey, cachedModsBeforeDeletion)
                        val remainingCachedMods = cachedModsBeforeDeletion.filterNot { it.cacheKey == target.cacheKey }
                        mutableState.value = mutableState.value.copy(
                            cachedMods = remainingCachedMods,
                            deploymentPlan = deploymentPlan.entries(remainingCachedMods),
                            feedback = FeedbackMessage(
                                "已删除 ${target.displayName} 的 Manager 私有缓存和部署计划；游戏内现有 Mod 未改变，如需移除请手动同步。",
                            ),
                        )
                    }
                    is CachedModDeletionResult.Rejected -> mutableState.value = mutableState.value.copy(
                        feedback = FeedbackMessage("删除 ${target.displayName} 失败：${result.reason}", isError = true),
                    )
                    is CachedModDeletionResult.Failed -> mutableState.value = mutableState.value.copy(
                        feedback = FeedbackMessage("删除 ${target.displayName} 失败：${result.reason}", isError = true),
                    )
                }
            } finally {
                mutableState.value = mutableState.value.copy(cachedModDeletionInProgress = false)
            }
        }
    }

    fun syncMods(allowExternalReplacement: Boolean) {
        if (mutableState.value.cachedModDeletionInProgress) {
            mutableState.value = mutableState.value.copy(
                feedback = FeedbackMessage("正在删除本地 Mod 缓存，请稍候。", isError = true),
            )
            return
        }
        mutableState.value = mutableState.value.copy(deploymentInProgress = true, feedback = null)
        viewModelScope.launch {
            try {
                val snapshot = deploymentPlan.snapshot(mutableState.value.cachedMods, allowExternalReplacement)
                applySnapshot(snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("同步到游戏失败：${error.message ?: "请重试。"}", isError = true),
                )
            } finally {
                mutableState.value = mutableState.value.copy(deploymentInProgress = false)
            }
        }
    }

    fun confirmStopGameAndSync() {
        val confirmation = mutableState.value.gameStopSyncConfirmation ?: return
        mutableState.value = mutableState.value.copy(gameStopSyncConfirmation = null, deploymentInProgress = true, feedback = null)
        viewModelScope.launch {
            try {
                val stopped = withContext(Dispatchers.IO) { loaderBridge.stopGameForSync() }
                if (!stopped.isReady) {
                    mutableState.value = mutableState.value.copy(
                        gameModStorage = stopped,
                        feedback = FeedbackMessage(stopped.reason ?: "无法关闭游戏，请手动关闭后重试。", isError = true),
                    )
                    return@launch
                }
                val snapshot = deploymentPlan.snapshot(mutableState.value.cachedMods, confirmation)
                applySnapshot(snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("关闭游戏后同步失败：${error.message ?: "请重试。"}", isError = true),
                )
            } finally {
                mutableState.value = mutableState.value.copy(deploymentInProgress = false)
            }
        }
    }

    fun dismissStopGameAndSyncConfirmation() {
        mutableState.value = mutableState.value.copy(gameStopSyncConfirmation = null)
    }

    private suspend fun applySnapshot(snapshot: com.sultansgame.modmanager.model.DeploymentSnapshot) {
        when (val result = withContext(Dispatchers.IO) { loaderBridge.requestApply(ApplyRequest(snapshot)) }) {
            is ApplyResult.Applied -> mutableState.value = mutableState.value.copy(
                gameModStorage = result.result.status,
                feedback = FeedbackMessage("已同步 ${snapshot.enabledEntries.size} 个启用 Mod；请退出并冷启动游戏。"),
            )
            is ApplyResult.Rejected -> {
                mutableState.value = mutableState.value.copy(
                    gameModStorage = result.status,
                    gameStopSyncConfirmation = if (result.status.failureCode == com.sultansgame.modmanager.model.ModStorageFailureCode.GameRunning) {
                        snapshot.allowExternalReplacement
                    } else {
                        null
                    },
                    feedback = FeedbackMessage(result.status.reason ?: "同步到游戏失败。", isError = true),
                )
            }
        }
    }

    fun launchGameForModService() {
        val intent = getApplication<Application>().packageManager
            .getLaunchIntentForPackage("com.gametree.sultan.pd")
        if (intent == null) {
            mutableState.value = mutableState.value.copy(
                feedback = FeedbackMessage("未找到游戏启动入口；请重新修补并安装匹配的游戏版本。", isError = true),
            )
            return
        }
        uiEventChannel.trySend(ManagerUiEvent.LaunchGameForModService(intent))
    }

    fun revokeGameModAuthorization() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { loaderBridge.revokeStorageAuthorization() }
            mutableState.value = mutableState.value.copy(
                gameModStorage = status,
                feedback = FeedbackMessage(status.reason ?: "已撤销游戏 Mod 管理授权。"),
            )
        }
    }

    private fun refreshDeploymentPlan() {
        mutableState.value = mutableState.value.copy(
            deploymentPlan = deploymentPlan.entries(mutableState.value.cachedMods),
        )
    }
    fun refreshGame() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { gameProbe.probe() }
            val currentPatch = mutableState.value.patch
            val nextPatch = when (currentPatch) {
                is PatchUiState.AwaitingOriginalUninstall -> when (result) {
                    GameProbeResult.NotInstalled -> PatchUiState.ReadyToInstall(
                        currentPatch.transactionId,
                        "已确认原版游戏未安装；可安装已准备的修补工件。",
                    )
                    is GameProbeResult.Found -> currentPatch.copy(
                        gameState = result,
                        summary = "原版游戏仍已安装；请先在系统界面完成卸载。",
                    )
                    is GameProbeResult.Failed -> currentPatch.copy(
                        gameState = result,
                        summary = "无法确认原版游戏状态；请重新检查后再继续。",
                    )
                }
                else -> currentPatch
            }
            mutableState.value = mutableState.value.copy(
                gameProbeResult = result,
                patch = nextPatch,
            )
        }
    }

    fun acceptLegalNotice() {
        viewModelScope.launch { legalNotice.acceptCurrentNotice() }
    }

    fun clearFeedback() {
        mutableState.value = mutableState.value.copy(feedback = null)
    }

    fun reportExternalImportError(reason: String) {
        mutableState.value = mutableState.value.copy(feedback = FeedbackMessage(reason, isError = true))
    }

    fun clearModCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { privateModCache.clear() }
            mutableState.value = mutableState.value.copy(
                cachedMods = emptyList(),
                deploymentPlan = emptyList(),
                feedback = FeedbackMessage("已清空 Manager 私有 Mod 缓存。"),
            )
        }
    }

    fun requestPatchCleanupConfirmation() {
        val candidate = mutableState.value.patchCleanup ?: return
        if (!canCleanPatchArtifacts()) return
        mutableState.value = mutableState.value.copy(patchCleanupConfirmation = candidate)
    }

    fun dismissPatchCleanupConfirmation() {
        mutableState.value = mutableState.value.copy(patchCleanupConfirmation = null)
    }

    fun confirmPatchCleanup() {
        if (mutableState.value.patchCleanup == null || !canCleanPatchArtifacts()) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                patchCleanupConfirmation = null,
                patchCleanupInProgress = true,
            )
            when (val result = withContext(Dispatchers.IO) {
                transactions.deleteCleanupWorkspaces(reservedPatchWorkspaceIds())
            }) {
                is PatchWorkspaceCleanupResult.Deleted -> {
                    if (selectedPatchInput?.extracted?.transactionId in result.workspaceIds) selectedPatchInput = null
                    val current = mutableState.value.patch
                    mutableState.value = mutableState.value.copy(
                        patch = current.resetIfWorkspaceWasDeleted(result.workspaceIds),
                        patchCleanupInProgress = false,
                        feedback = FeedbackMessage("已清理修补临时文件；已导出的 APKS 未受影响。"),
                    )
                    refreshPatchWorkspaceState()
                }
                PatchWorkspaceCleanupResult.NothingToDelete -> {
                    mutableState.value = mutableState.value.copy(patchCleanupInProgress = false)
                    refreshPatchWorkspaceState()
                }
                is PatchWorkspaceCleanupResult.Failed -> {
                    mutableState.value = mutableState.value.copy(
                        patchCleanupInProgress = false,
                        feedback = FeedbackMessage("清理修补临时文件失败：${result.reason}", isError = true),
                    )
                    refreshPatchWorkspaceState()
                }
            }
        }
    }

    fun importZip(uri: Uri) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("正在校验并导入 ZIP Mod…"))
            runCatching { withContext(Dispatchers.IO) { zipImporter.importZip(uri) } }
                .onSuccess(::updateImportedMods)
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        feedback = FeedbackMessage("ZIP 导入失败：${error.message ?: "无法验证内容"}", isError = true),
                    )
                }
        }
    }

    fun receiveExternalZip(uri: Uri) {
        if (mutableState.value.pendingExternalZip != null) {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("请先处理当前待导入的外部 ZIP 文件。", isError = true))
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("正在安全接收外部 ZIP 文件…"))
            runCatching { withContext(Dispatchers.IO) { externalZipInbox.receive(uri) } }
                .onSuccess { request ->
                    mutableState.value = mutableState.value.copy(
                        pendingExternalZip = request,
                        feedback = null,
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        feedback = FeedbackMessage("无法接收外部 ZIP：${error.message ?: "请重试"}", isError = true),
                    )
                }
        }
    }

    fun confirmExternalZipImport() {
        val request = mutableState.value.pendingExternalZip ?: return
        mutableState.value = mutableState.value.copy(pendingExternalZip = null, feedback = FeedbackMessage("正在校验并导入 ${request.displayName}…"))
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { zipImporter.importZip(externalZipInbox.fileFor(request)) }
                updateImportedMods(imported)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("ZIP 导入失败：${error.message ?: "无法验证内容"}", isError = true),
                )
            } finally {
                withContext(Dispatchers.IO) { externalZipInbox.discard(request) }
            }
        }
    }

    fun cancelExternalZipImport() {
        val request = mutableState.value.pendingExternalZip ?: return
        mutableState.value = mutableState.value.copy(pendingExternalZip = null)
        viewModelScope.launch(Dispatchers.IO) { externalZipInbox.discard(request) }
    }

    private fun updateImportedMods(imported: List<com.sultansgame.modmanager.model.CachedMod>) {
        val cachedMods = (mutableState.value.cachedMods + imported).distinctBy { it.cacheKey }
        mutableState.value = mutableState.value.copy(
            cachedMods = cachedMods,
            deploymentPlan = deploymentPlan.entries(cachedMods),
            feedback = FeedbackMessage(
                "已安全缓存 ${imported.size} 个 Mod：${imported.joinToString { it.displayName }}；可在 Mod 页面启用并同步到游戏。",
            ),
        )
    }

    fun beginSteamLogin(username: String, password: String, rememberSession: Boolean) {
        viewModelScope.launch {
            handleAuthResult(steamAuth.beginLogin(SteamCredentials(username, password, rememberSession)))
        }
    }

    fun submitSteamGuard(code: String) {
        if (steamGuardSubmissionJob?.isActive == true) return
        steamGuardSubmissionJob = viewModelScope.launch {
            handleAuthResult(steamAuth.submitSteamGuard(code))
        }
    }

    fun checkPendingSteamLogin() {
        if (steamGuardSubmissionJob?.isActive == true) return
        steamGuardSubmissionJob = viewModelScope.launch {
            handleAuthResult(steamAuth.checkPendingLogin())
        }
    }

    fun logoutSteam() {
        viewModelScope.launch { handleAuthResult(steamAuth.logout()) }
    }

    fun browseWorkshop(query: com.sultansgame.modmanager.model.WorkshopBrowseQuery = com.sultansgame.modmanager.model.WorkshopBrowseQuery()) {
        val normalizedQuery = query.normalized()
        workshopBrowseJob?.cancel()
        val generation = ++workshopBrowseGeneration
        workshopBrowseJob = viewModelScope.launch {
            val current = mutableState.value.workshopBrowse
            val append = isWorkshopBrowseAppend(current.query, normalizedQuery)
            mutableState.value = mutableState.value.copy(
                workshopBrowse = current.copy(
                    query = normalizedQuery,
                    isRefreshing = !append,
                    isLoadingMore = append,
                    error = null,
                ),
            )
            runCatching { withContext(Dispatchers.IO) { communityWorkshopBrowser.browse(normalizedQuery) } }
                .onSuccess { page ->
                    if (generation != workshopBrowseGeneration) return@onSuccess
                    val previous = mutableState.value.workshopBrowse
                    val items = if (append) {
                        (previous.items + page.items).distinctBy(WorkshopItem::publishedFileId)
                    } else {
                        page.items
                    }
                    mutableState.value = mutableState.value.copy(
                        workshopBrowse = WorkshopBrowseUiState(
                            query = normalizedQuery,
                            items = items,
                            totalCount = page.totalCount,
                            hasMore = page.hasMore,
                            sectionOptions = page.sectionOptions,
                            sortOptions = page.sortOptions,
                            periodOptions = page.periodOptions,
                            tagGroups = page.tagGroups,
                            supportsIncompatibleFilter = page.supportsIncompatibleFilter,
                            hasLoadedOnce = true,
                        ),
                    )
                }
                .onFailure { error ->
                    if (generation != workshopBrowseGeneration || error is kotlinx.coroutines.CancellationException) return@onFailure
                    val previous = mutableState.value.workshopBrowse
                    mutableState.value = mutableState.value.copy(
                        workshopBrowse = previous.copy(
                            query = normalizedQuery,
                            isRefreshing = false,
                            isLoadingMore = false,
                            hasLoadedOnce = true,
                            error = error.message ?: "无法读取 Steam 创意工坊。",
                        ),
                    )
                }
        }
    }

    private fun isWorkshopBrowseAppend(
        currentQuery: com.sultansgame.modmanager.model.WorkshopBrowseQuery,
        requestedQuery: com.sultansgame.modmanager.model.WorkshopBrowseQuery,
    ): Boolean = requestedQuery.page > currentQuery.page &&
        currentQuery.copy(page = 1) == requestedQuery.copy(page = 1)
    fun lookupWorkshop(rawId: String) {
        if (rawId.isBlank()) {
            mutableState.value = mutableState.value.copy(workshop = WorkshopUiState.Idle)
            return
        }
        val id = PublishedFileId.parse(rawId)
        if (id == null) {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("PublishedFileId 必须是正整数。", isError = true))
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(workshop = WorkshopUiState.Loading)
            val lookup = withContext(Dispatchers.IO) {
                workshopProvider.getItemWithCommunityDetail(SULTANS_GAME_APP_ID, id)
            }
            mutableState.value = mutableState.value.copy(
                workshop = when (lookup) {
                    is WorkshopLookupResult.Available -> WorkshopUiState.Item(lookup.item)
                    is WorkshopLookupResult.Unavailable -> WorkshopUiState.Error(lookup.reason)
                },
            )
        }
    }

    fun queueWorkshopDownload(item: WorkshopItem) {
        if (item.appId != SULTANS_GAME_APP_ID) {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("只允许下载《苏丹的游戏》创意工坊条目。", isError = true))
            return
        }
        if (mutableState.value.downloadTasks.any { it.publishedFileId == item.publishedFileId && it.stage in NON_DUPLICABLE_DOWNLOAD_STAGES }) {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("该条目已在下载队列中，请前往下载中心查看状态。"))
            return
        }
        val account = steamAuthProvider.persistentSession()
        if (account == null) {
            mutableState.value = mutableState.value.copy(
                feedback = FeedbackMessage("下载创意工坊内容需要已保存的 Steam 登录状态；请登录并勾选“记住登录状态”。", isError = true),
            )
            return
        }
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            appId = SULTANS_GAME_APP_ID,
            publishedFileId = item.publishedFileId,
            accessMode = WorkshopAccessMode.Account,
            boundAccountHash = steamAccountBindingHash(account.steamId),
            stage = DownloadStage.Queued,
            title = item.title,
            totalBytes = item.declaredSizeBytes,
        )
        viewModelScope.launch {
            if (!taskStore.create(task)) {
                mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("下载任务创建失败，请重试。", isError = true))
                return@launch
            }
            downloadScheduler.enqueue(requireNotNull(taskStore.getPersisted(task.id)))
            mutableState.value = mutableState.value.copy(
                feedback = FeedbackMessage(
                    if (task.accessMode == WorkshopAccessMode.Account) {
                        "已使用已保存的 Steam 登录状态将 ${item.title} 加入下载队列。"
                    } else {
                        "已将 ${item.title} 加入下载队列。"
                    },
                ),
            )
        }
    }

    fun retryWorkshopDownload(taskId: String) {
        viewModelScope.launch {
            taskStore.requestRetry(taskId)?.let(downloadScheduler::enqueue)
        }
    }

    fun pauseWorkshopDownload(taskId: String) {
        viewModelScope.launch {
            if (taskStore.requestPause(taskId)) {
                downloadScheduler.cancel(taskId)
            }
        }
    }

    fun resumeWorkshopDownload(taskId: String) {
        viewModelScope.launch {
            taskStore.requestRetry(taskId)?.let(downloadScheduler::enqueue)
        }
    }

    fun cancelWorkshopDownload(taskId: String) {
        viewModelScope.launch {
            if (taskStore.requestCancel(taskId)) {
                downloadScheduler.cancel(taskId)
            }
        }
    }

    fun removeWorkshopDownload(taskId: String) {
        viewModelScope.launch {
            val task = taskStore.getPersisted(taskId)
            if (task == null) {
                mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("下载任务已不存在。", isError = true))
                return@launch
            }
            if (task.stage == DownloadStage.Importing) {
                mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("正在导入 Mod，暂时不能删除下载任务。", isError = true))
                return@launch
            }
            taskStore.requestCancel(taskId)
            downloadScheduler.cancel(taskId)
            val removed = taskStore.takeForDeletion(taskId)
            if (removed == null) {
                mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("下载任务状态已变化，未删除。", isError = true))
                return@launch
            }
            runCatching { withContext(Dispatchers.IO) { artifactImporter.discard(removed) } }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("已删除下载任务并清理私有暂存内容。"))
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        feedback = FeedbackMessage("下载任务已删除，但清理私有暂存内容失败：${error.message ?: "请稍后重试。"}", isError = true),
                    )
                }
        }
    }

    fun confirmWorkshopImport(taskId: String) {
        viewModelScope.launch {
            val pendingTask = taskStore.getPersisted(taskId) ?: return@launch
            try {
                withContext(Dispatchers.IO) { artifactImporter.verifyPendingArtifact(pendingTask) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                taskStore.invalidateArtifact(taskId)
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("下载内容校验失败：${error.message ?: "必须重新下载"}", isError = true),
                )
                return@launch
            }

            val task = taskStore.beginImport(taskId) ?: return@launch
            try {
                val cached = withContext(Dispatchers.IO) { artifactImporter.importConfirmed(task) }
                if (taskStore.finishImport(taskId)) {
                    val cachedMods = (mutableState.value.cachedMods + cached).distinctBy { it.cacheKey }
                    mutableState.value = mutableState.value.copy(
                        cachedMods = cachedMods,
                        deploymentPlan = deploymentPlan.entries(cachedMods),
                        feedback = FeedbackMessage("已安全缓存 ${cached.displayName}；尚未同步到游戏。"),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                taskStore.failImport(taskId)
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("下载内容未能导入：${error.message ?: "无法验证内容"}", isError = true),
                )
            }
        }
    }

    fun discardWorkshopArtifact(taskId: String) {
        viewModelScope.launch {
            val task = taskStore.getPersisted(taskId) ?: return@launch
            if (task.stage != DownloadStage.AwaitingImportConfirmation || !taskStore.requestCancel(taskId)) return@launch
            withContext(Dispatchers.IO) { artifactImporter.discard(task) }
        }
    }

    fun updatePatchConfirmation(confirmation: PatchConfirmation) {
        val current = mutableState.value.patch as? PatchUiState.Review ?: return
        mutableState.value = mutableState.value.copy(patch = current.copy(confirmation = confirmation))
    }

    fun selectInstalledGameSource() {
        if (mutableState.value.patch !is PatchUiState.ChooseSource) return
        if (mutableState.value.gameProbeResult !is GameProbeResult.Found) {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("未检测到已安装的目标游戏；请选择本地 APK 或 APKS。", isError = true))
            return
        }
        importPatchInput("正在读取已安装游戏…", PatchSource.InstalledGame, "已安装游戏") {
            apkExtractor.extract("com.gametree.sultan.pd")
        }
    }

    fun importLocalApk(uri: Uri, displayName: String) {
        if (mutableState.value.patch !is PatchUiState.ChooseSource) return
        importPatchInput("正在导入 $displayName…", PatchSource.SelectedApk, displayName) {
            requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) { "无法读取所选 APK。" }.use { input ->
                apkExtractor.importSingle(input, displayName)
            }
        }
    }

    fun importLocalApkSet(uri: Uri, displayName: String) {
        if (mutableState.value.patch !is PatchUiState.ChooseSource) return
        importPatchInput("正在导入 $displayName…", PatchSource.SelectedApks, displayName) {
            requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) { "无法读取所选 APKS。" }.use { input ->
                apkExtractor.importApkSet(input, displayName)
            }
        }
    }

    fun restartPatchFlow() {
        selectedPatchInput = null
        mutableState.value = mutableState.value.copy(patch = PatchUiState.ChooseSource)
        refreshPatchWorkspaceState()
    }

    fun resumePreparedPatch(transactionId: String) {
        val recovery = mutableState.value.preparedPatchRecovery ?: return
        if (recovery.transactionId != transactionId || mutableState.value.patch !is PatchUiState.ChooseSource) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(patch = PatchUiState.Importing("正在验证已准备的修补 APK…"))
            val result = withContext(Dispatchers.IO) { orchestrator.resumePreparedArtifacts(transactionId) }
            applyOrchestrationResult(result, null, null)
            if (result is PatchOrchestrationResult.Failed) {
                mutableState.value = mutableState.value.copy(preparedPatchRecovery = null)
            }
        }
    }

    fun preparePatchArtifacts() {
        val review = mutableState.value.patch as? PatchUiState.Review ?: return
        val selected = selectedPatchInput ?: return
        if (!review.confirmation.permits(review.input.classification.mode)) return
        if (review.input.classification.compatibility.compatibility == com.sultansgame.modmanager.model.Compatibility.Unsupported) return
        if (deviceSigningKeyStore.state() == com.sultansgame.modmanager.model.DeviceSigningKeyState.MissingAfterMigration) {
            mutableState.value = mutableState.value.copy(
                patch = PatchUiState.Failed("设备签名密钥已丢失，必须卸载旧迁移版游戏后重新开始。"),
            )
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(patch = PatchUiState.Preparing(review.input))
            val result = withContext(Dispatchers.IO) {
                orchestrator.submit(selected.source, selected.extracted, review.confirmation)
            }
            applyOrchestrationResult(result, review.input, review.confirmation)
        }
    }

    fun exportPreparedApks(transactionId: String) {
        if (!canExportPreparedApks(transactionId) || mutableState.value.apksExport !is ApksExportUiState.Idle) return
        mutableState.value = mutableState.value.copy(
            apksExport = ApksExportUiState.SelectingDestination(transactionId),
        )
        val suggestedName = "sultans-game-patched-${transactionId.take(8)}.apks"
        if (uiEventChannel.trySend(ManagerUiEvent.CreateApksExport(transactionId, suggestedName)).isFailure) {
            mutableState.value = mutableState.value.copy(apksExport = ApksExportUiState.Idle)
        }
    }

    fun cancelPreparedApksExport(transactionId: String) {
        if (mutableState.value.apksExport.transactionIdOrNull() == transactionId) {
            mutableState.value = mutableState.value.copy(apksExport = ApksExportUiState.Idle)
        }
    }

    fun writePreparedApks(transactionId: String, uri: Uri?) {
        if (uri == null) {
            cancelPreparedApksExport(transactionId)
            return
        }
        if (!canExportPreparedApks(transactionId) ||
            mutableState.value.apksExport !is ApksExportUiState.SelectingDestination
        ) return
        mutableState.value = mutableState.value.copy(apksExport = ApksExportUiState.Validating(transactionId))
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val application = getApplication<Application>()
                    val displayName = application.contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        cursor.takeIf { it.moveToFirst() }
                            ?.getString(cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME))
                    } ?: uri.lastPathSegment
                    require(!displayName.isNullOrBlank() && displayName.endsWith(".apks", ignoreCase = true)) {
                        "所选文件名必须以 .apks 结尾；请在文件选择器中修改名称后重试。"
                    }
                    requireNotNull(application.contentResolver.openOutputStream(uri)) {
                        "无法写入所选导出位置。"
                    }.use { output ->
                        ApksExporter(transactions).export(transactionId, output) { progress ->
                            mutableState.value = mutableState.value.copy(
                                apksExport = progress.toUiState(transactionId),
                            )
                        }
                    }
                }
                refreshPatchWorkspaceState()
                mutableState.value = mutableState.value.copy(
                    apksExport = ApksExportUiState.Idle,
                    patchCleanupConfirmation = null,
                    feedback = FeedbackMessage("已导出修补 APKS；请使用支持 APKS 的安装工具安装。"),
                )
            } catch (error: CancellationException) {
                mutableState.value = mutableState.value.copy(apksExport = ApksExportUiState.Idle)
                throw error
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(
                    apksExport = ApksExportUiState.Idle,
                    feedback = FeedbackMessage(
                        "导出 APKS 失败：${error.message ?: "无法写入文件"}；目标位置可能留下不完整文件，请勿安装。",
                        isError = true,
                    ),
                )
            }
        }
    }

    private fun canExportPreparedApks(transactionId: String): Boolean = when (val current = mutableState.value.patch) {
        is PatchUiState.AwaitingOriginalUninstall -> current.transactionId == transactionId
        is PatchUiState.ReadyToInstall -> current.transactionId == transactionId
        is PatchUiState.AwaitingInstallPermission -> current.transactionId == transactionId
        else -> false
    }

    private fun ApksExportUiState.transactionIdOrNull(): String? = when (this) {
        ApksExportUiState.Idle -> null
        is ApksExportUiState.SelectingDestination -> transactionId
        is ApksExportUiState.Validating -> transactionId
        is ApksExportUiState.Writing -> transactionId
    }

    private fun ApksExportProgress.toUiState(transactionId: String): ApksExportUiState = when (this) {
        ApksExportProgress.Validating -> ApksExportUiState.Validating(transactionId)
        is ApksExportProgress.Writing -> ApksExportUiState.Writing(
            transactionId = transactionId,
            artifactName = artifactName,
            completedArtifacts = artifactIndex + 1,
            artifactCount = artifactCount,
            writtenBytes = writtenBytes,
            totalBytes = totalBytes,
        )
    }

    fun refreshPendingPatchState() {        when (mutableState.value.patch) {
            is PatchUiState.AwaitingOriginalUninstall -> refreshGame()
            else -> Unit
        }
    }

    fun requestOriginalGameUninstall(transactionId: String) {
        val current = mutableState.value.patch as? PatchUiState.AwaitingOriginalUninstall ?: return
        if (current.transactionId != transactionId || current.gameState !is GameProbeResult.Found) return
        uiEventChannel.trySend(ManagerUiEvent.OpenGameUninstall(transactionId))
    }

    fun installPreparedArtifacts(transactionId: String) {
        if (mutableState.value.apksExport !is ApksExportUiState.Idle) return
        val current = mutableState.value.patch as? PatchUiState.ReadyToInstall ?: return
        if (current.transactionId != transactionId) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(patch = PatchUiState.SubmittingInstall(transactionId))
            val result = withContext(Dispatchers.IO) { orchestrator.submitPreparedArtifacts(transactionId) }
            applyOrchestrationResult(result, null, null)
        }
    }

    fun refreshInstallPermission() {
        val current = mutableState.value.patch as? PatchUiState.AwaitingInstallPermission ?: return
        if (!packageInstaller.canRequestInstalls()) return
        mutableState.value = mutableState.value.copy(
            patch = current.transactionId?.let { transactionId ->
                PatchUiState.ReadyToInstall(transactionId, "已获得安装授权；请确认后安装已准备的修补工件。")
            } ?: current.input?.let { input ->
                PatchUiState.Review(input, requireNotNull(current.confirmation))
            } ?: PatchUiState.ChooseSource,
        )
    }

    fun openUnknownSourcesSettings() {
        if (mutableState.value.patch !is PatchUiState.AwaitingInstallPermission) return
        uiEventChannel.trySend(ManagerUiEvent.OpenUnknownSourcesSettings(packageInstaller.unknownSourcesSettingsIntent()))
    }

    fun onGameUninstallResult() = refreshPendingPatchState()

    private fun restorePatchUiState(transaction: com.sultansgame.modmanager.platform.patch.PatchTransaction): PatchUiState = when (transaction.stage) {
        com.sultansgame.modmanager.model.PatchStage.AwaitingGameUninstall -> PatchUiState.AwaitingOriginalUninstall(
            transactionId = transaction.id,
            gameState = null,
            summary = "已恢复已准备的修补工件；正在检查原版游戏状态…",
        )
        com.sultansgame.modmanager.model.PatchStage.AwaitingInstallPermission -> PatchUiState.AwaitingInstallPermission(
            transactionId = transaction.id,
            input = null,
            confirmation = null,
        )
        else -> PatchUiState.ChooseSource
    }

    private fun importPatchInput(
        progressLabel: String,
        source: PatchSource,
        sourceLabel: String,
        importer: () -> com.sultansgame.modmanager.platform.patch.ExtractedApkSet,
    ) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(patch = PatchUiState.Importing(progressLabel))
            runCatching { withContext(Dispatchers.IO) { importer() } }
                .onSuccess { extracted ->
                    val classification = profileRegistry.classify(
                        source,
                        extracted.base.inspection,
                        trustedDeviceCertificateSha256 = deviceSigningKeyStore.certificateSha256(),
                    )
                    val inspection = extracted.base.inspection
                    val input = PatchInputUiModel(
                        source = source,
                        sourceLabel = sourceLabel,
                        versionLabel = inspection.versionName ?: inspection.versionCode?.toString() ?: "未知版本",
                        splitCount = extracted.splits.size,
                        signerSummary = inspection.signerDigestsSha256.firstOrNull()?.take(12)?.plus("…") ?: "未读取到签名",
                        classification = classification,
                    )
                    selectedPatchInput = SelectedPatchInput(source, extracted, input)
                    mutableState.value = mutableState.value.copy(patch = PatchUiState.Review(input))
                    refreshPatchWorkspaceState()
                }
                .onFailure { error ->
                    selectedPatchInput = null
                    mutableState.value = mutableState.value.copy(
                        patch = PatchUiState.Failed("导入 APK 失败：${error.message ?: "无法验证所选内容"}"),
                    )
                }
        }
    }

    private suspend fun handleInstallResult(intent: android.content.Intent) {
        val result = orchestrator.handleInstallResult(intent) ?: return
        applyOrchestrationResult(result, null, null)
    }

    private suspend fun applyOrchestrationResult(
        result: PatchOrchestrationResult,
        input: PatchInputUiModel?,
        confirmation: PatchConfirmation?,
    ) {
        val patch = when (result) {
            is PatchOrchestrationResult.AwaitingConfirmation -> {
                input?.let { PatchUiState.Review(it, confirmation ?: PatchConfirmation()) }
                    ?: PatchUiState.Failed(result.reason)
            }
            is PatchOrchestrationResult.NeedsInstallPermission -> PatchUiState.AwaitingInstallPermission(
                transactionId = result.transactionId,
                input = input,
                confirmation = confirmation,
            )
            is PatchOrchestrationResult.NeedsGameUninstall -> {
                val currentGameState = withContext(Dispatchers.IO) { gameProbe.probe() }
                mutableState.value = mutableState.value.copy(gameProbeResult = currentGameState)
                when (currentGameState) {
                    GameProbeResult.NotInstalled -> PatchUiState.ReadyToInstall(
                        result.transactionId,
                        "已确认原版游戏未安装；可安装已准备的修补工件。",
                    )
                    else -> PatchUiState.AwaitingOriginalUninstall(
                        transactionId = result.transactionId,
                        gameState = currentGameState,
                        summary = "签名工件已准备。请先在系统界面卸载当前游戏，再返回此处继续。",
                    )
                }
            }
            is PatchOrchestrationResult.AwaitingSystemInstall -> PatchUiState.AwaitingSystemInstall(result.transactionId)
            is PatchOrchestrationResult.NeedsUserAction -> {
                uiEventChannel.trySend(ManagerUiEvent.ConfirmPackageInstall(result.intent))
                PatchUiState.AwaitingSystemInstall(result.transactionId)
            }
            is PatchOrchestrationResult.AwaitingVerification -> PatchUiState.AwaitingSystemInstall(result.transactionId)
            is PatchOrchestrationResult.Completed -> {
                selectedPatchInput = null
                refreshGame()
                PatchUiState.Completed(result.transactionId)
            }
            is PatchOrchestrationResult.Failed -> PatchUiState.Failed(result.reason, result.transactionId)
        }
        mutableState.value = mutableState.value.copy(
            patch = patch,
            preparedPatchRecovery = when (result) {
                is PatchOrchestrationResult.NeedsInstallPermission,
                is PatchOrchestrationResult.NeedsGameUninstall,
                is PatchOrchestrationResult.AwaitingSystemInstall,
                is PatchOrchestrationResult.Completed -> null
                else -> mutableState.value.preparedPatchRecovery
            },
            patchCleanupConfirmation = null,
            deviceSigningKeyState = deviceSigningKeyStore.state(),
            feedback = (result as? PatchOrchestrationResult.Failed)?.let { FeedbackMessage("迁移失败：${it.reason}", isError = true) }
                ?: mutableState.value.feedback,
        )
        refreshPatchWorkspaceState()
    }

    private fun canCleanPatchArtifacts(): Boolean =
        !mutableState.value.patchCleanupInProgress &&
            mutableState.value.apksExport is ApksExportUiState.Idle &&
            mutableState.value.patch !is PatchUiState.Importing &&
            mutableState.value.patch !is PatchUiState.Preparing &&
            mutableState.value.patch !is PatchUiState.ReadyToInstall &&
            mutableState.value.patch !is PatchUiState.SubmittingInstall &&
            mutableState.value.patch !is PatchUiState.AwaitingSystemInstall

    private fun reservedPatchWorkspaceIds(): Set<String> = buildSet {
        if (mutableState.value.patch is PatchUiState.Review || mutableState.value.patch is PatchUiState.Preparing) {
            selectedPatchInput?.extracted?.transactionId?.let(::add)
        }
        when (val patch = mutableState.value.patch) {
            is PatchUiState.AwaitingSystemInstall -> add(patch.transactionId)
            is PatchUiState.SubmittingInstall -> add(patch.transactionId)
            else -> Unit
        }
    }

    private fun refreshPatchWorkspaceState() {
        val recovery = transactions.latestPreparedForRecovery()
        val cleanupSummary = transactions.cleanupSummary(reservedPatchWorkspaceIds())
        mutableState.value = mutableState.value.copy(
            preparedPatchRecovery = recovery?.toRecoveryUiModel(),
            patchCleanup = cleanupSummary?.toCleanupUiModel(),
        )
    }

    private fun PatchUiState.resetIfWorkspaceWasDeleted(workspaceIds: Set<String>): PatchUiState = when (this) {
        is PatchUiState.AwaitingOriginalUninstall -> if (transactionId in workspaceIds) PatchUiState.ChooseSource else this
        is PatchUiState.ReadyToInstall -> if (transactionId in workspaceIds) PatchUiState.ChooseSource else this
        is PatchUiState.AwaitingInstallPermission -> if (transactionId in workspaceIds) PatchUiState.ChooseSource else this
        is PatchUiState.Completed -> if (transactionId in workspaceIds) PatchUiState.ChooseSource else this
        is PatchUiState.Failed -> if (transactionId in workspaceIds) PatchUiState.ChooseSource else this
        else -> this
    }

    private fun com.sultansgame.modmanager.platform.patch.PatchTransaction.toRecoveryUiModel() =
        PreparedPatchRecovery(
            transactionId = id,
            summary = "发现已准备的修补 APK；继续前会校验工件和设备签名身份。",
        )

    private fun PatchWorkspaceCleanupSummary.toCleanupUiModel() = PatchCleanupUiModel(
        workspaceIds = workspaceIds,
        sizeBytes = sizeBytes,
    )

    private companion object {
        val NON_DUPLICABLE_DOWNLOAD_STAGES = setOf(
            DownloadStage.Queued,
            DownloadStage.ResolvingMetadata,
            DownloadStage.AwaitingPublicUrl,
            DownloadStage.Downloading,
            DownloadStage.Paused,
            DownloadStage.Verifying,
            DownloadStage.AwaitingImportConfirmation,
            DownloadStage.Importing,
            DownloadStage.NeedsLogin,
        )
        val DOWNLOAD_STAGES_TO_RESCHEDULE = setOf(
            DownloadStage.Queued,
            DownloadStage.ResolvingMetadata,
            DownloadStage.AwaitingPublicUrl,
            DownloadStage.Downloading,
            DownloadStage.Verifying,
        )
    }

    private fun handleAuthResult(result: com.sultansgame.modmanager.platform.auth.SteamAuthResult) {
        when (result) {
            is com.sultansgame.modmanager.platform.auth.SteamAuthResult.Failed,
            is com.sultansgame.modmanager.platform.auth.SteamAuthResult.Unavailable -> {
                val reason = when (result) {
                    is com.sultansgame.modmanager.platform.auth.SteamAuthResult.Failed -> result.reason
                    is com.sultansgame.modmanager.platform.auth.SteamAuthResult.Unavailable -> result.reason
                }
                mutableState.value = mutableState.value.copy(feedback = FeedbackMessage(reason, isError = true))
            }
            is com.sultansgame.modmanager.platform.auth.SteamAuthResult.SignedIn -> {
                mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("已登录 Steam：${result.accountName}"))
            }
            is com.sultansgame.modmanager.platform.auth.SteamAuthResult.SteamGuardRequired -> {
                mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("需要 Steam Guard：${result.challenge}"))
            }
            is com.sultansgame.modmanager.platform.auth.SteamAuthResult.AwaitingConfirmation -> {
                mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("请在 Steam 中完成确认：${result.challenge}"))
            }
            com.sultansgame.modmanager.platform.auth.SteamAuthResult.Cleared -> {
                mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("已退出 Steam 登录。"))
            }
        }
    }
}
