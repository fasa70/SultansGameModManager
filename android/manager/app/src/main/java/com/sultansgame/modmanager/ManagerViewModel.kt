package com.sultansgame.modmanager

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.sultansgame.modmanager.platform.storage.AndroidStorageSpaceProbe
import com.sultansgame.modmanager.platform.storage.CachedModDeletionResult
import com.sultansgame.modmanager.platform.storage.DeploymentPlanStore
import com.sultansgame.modmanager.storage.StorageBudget
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

sealed interface ManagerUiEvent {
    data class LaunchGameForModSync(val intent: android.content.Intent) : ManagerUiEvent
    data class OpenGameUninstall(val transactionId: String) : ManagerUiEvent
    data class OpenUnknownSourcesSettings(val intent: android.content.Intent) : ManagerUiEvent
    data class ConfirmPackageInstall(val intent: android.content.Intent) : ManagerUiEvent
    data class CreateApksExport(val transactionId: String, val suggestedName: String) : ManagerUiEvent
    data class OpenExternalUrl(val url: String) : ManagerUiEvent
}

class ManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val storageBudget = StorageBudget(AndroidStorageSpaceProbe())
    private val privateModCache = AndroidPrivateModCache(File(application.filesDir, "mod-cache"), storageBudget, application)
    private val deploymentPlan = DeploymentPlanStore(application)
    private val zipImporter = ZipModImporter(application, privateModCache, storageBudget)
    private val externalZipInbox = ExternalZipInbox(application, storageBudget)
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
        splitFactory = AndroidLoaderSplitArtifactFactory(application),
    )
    private val loaderBridge: LoaderBridge = AndroidModStorageLoaderBridge(application, File(application.filesDir, "mod-cache"))
    private val legalNotice = LegalNoticeRepository(application)
    private val updateCheckSettings = UpdateCheckSettingsRepository(application)
    private val updateChecker: UpdateChecker = GitHubReleaseUpdateChecker()
    private val mergeEngine = com.sultansgame.modmanager.merge.ModMergeEngine()
    private val mergeRoot = File(application.cacheDir, "mod-merge")
    private val mergeCatalog = loadMergeCatalog()

    private fun loadMergeCatalog(): com.sultansgame.modmanager.merge.BaseIdCatalog = runCatching {
        getApplication<Application>().assets.open("merge/base-id-catalog-10005.json").use { input ->
            com.sultansgame.modmanager.merge.BaseIdCatalogJsonCodec().decode(input.readBytes().toString(Charsets.UTF_8))
        }
    }.getOrElse {
        com.sultansgame.modmanager.merge.BaseIdCatalog(
            profileId = "official-android-2026-07-27",
            versionCode = 10005L,
            catalogVersion = "android-10005-unavailable",
        )
    }

    private val mutableState = MutableStateFlow(ManagerUiState())
    val state: StateFlow<ManagerUiState> = mutableState.asStateFlow()
    private val uiEventChannel = Channel<ManagerUiEvent>(Channel.BUFFERED)
    val uiEvents = uiEventChannel.receiveAsFlow()
    private var selectedPatchInput: SelectedPatchInput? = null

    private data class SelectedPatchInput(
        val source: PatchSource,
        val extracted: com.sultansgame.modmanager.platform.patch.ExtractedApkSet,
        val uiModel: PatchInputUiModel,
        val trustedDeviceCertificateSha256: String?,
    )

    private var workshopBrowseJob: Job? = null
    private var steamGuardSubmissionJob: Job? = null
    private var workshopBrowseGeneration = 0L
    private var gameModSyncJob: Job? = null
    private var updateCheckJob: Job? = null
    private var updateCheckEnabled = false

    init {
        privateModCache.recoverInterruptedImports()
        externalZipInbox.recoverInterruptedReceipts()
        val cachedMods = privateModCache.listCached()
        val pendingPatch = transactions.latestPreparedForRecovery()
        val cleanupCandidate = transactions.cleanupSummary(emptySet())
        deploymentPlan.ensureSynced(cachedMods)
        mutableState.value = mutableState.value.copy(
            cachedMods = cachedMods,
            gameModSyncItems = deploymentPlan.entries(cachedMods),
            pendingGameModSyncOperations = deploymentPlan.pendingOperations(),
            downloadTasks = taskStore.tasks.value,
            deviceSigningKeyState = deviceSigningKeyStore.state(),
            preparedPatchRecovery = pendingPatch?.toRecoveryUiModel(),
            patchCleanup = cleanupCandidate?.toCleanupUiModel(),
        )
        refreshGame()
        refreshGameModSync()
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
            val enabled = updateCheckSettings.isAutoCheckEnabled.first()
            updateCheckEnabled = enabled
            mutableState.value = mutableState.value.copy(autoUpdateCheckEnabled = enabled)
            if (enabled) checkForUpdateAtStartup()
        }
        viewModelScope.launch {
            PatchInstallResults.results.collect { intent ->
                handleInstallResult(intent)
            }
        }
    }

    fun openMerge() {
        mutableState.value = mutableState.value.copy(
            merge = mutableState.value.merge.copy(
                isOpen = true,
                selectedCacheKeys = emptyList(),
                catalogSelection = com.sultansgame.modmanager.merge.CatalogSelection(mergeCatalog, exactVersion = true),
                conflicts = emptyList(),
                progress = null,
                resultCacheKey = null,
                awaitingSyncDecision = false,
            ),
        )
    }

    fun closeMerge() {
        if (mutableState.value.merge.isRunning) return
        mutableState.value = mutableState.value.copy(merge = mutableState.value.merge.copy(isOpen = false))
    }

    fun toggleMergeMod(cacheKey: String) {
        val current = mutableState.value.merge.selectedCacheKeys
        val next = if (cacheKey in current) current - cacheKey else current + cacheKey
        mutableState.value = mutableState.value.copy(merge = mutableState.value.merge.copy(selectedCacheKeys = next))
        refreshMergePreflight()
    }

    fun moveMergeMod(from: Int, to: Int) {
        val current = mutableState.value.merge.selectedCacheKeys.toMutableList()
        if (from !in current.indices || to !in current.indices) return
        val value = current.removeAt(from)
        current.add(to, value)
        mutableState.value = mutableState.value.copy(merge = mutableState.value.merge.copy(selectedCacheKeys = current))
        refreshMergePreflight()
    }

    private fun refreshMergePreflight() {
        val merge = mutableState.value.merge
        if (merge.selectedCacheKeys.size < 2 || merge.catalogSelection == null) {
            mutableState.value = mutableState.value.copy(merge = merge.copy(conflicts = emptyList()))
            return
        }
        val roots = merge.selectedCacheKeys.map { File(File(getApplication<Application>().filesDir, "mod-cache"), it) }
        val preflight = runCatching { mergeEngine.preflight(roots, merge.catalogSelection) }.getOrNull()
        mutableState.value = mutableState.value.copy(merge = merge.copy(conflicts = preflight?.conflicts.orEmpty()))
    }

    fun setMergeDisplayName(value: String) {
        mutableState.value = mutableState.value.copy(merge = mutableState.value.merge.copy(resultDisplayName = value))
    }

    fun startMerge() {
        val merge = mutableState.value.merge
        val selection = merge.catalogSelection ?: return
        if (merge.selectedCacheKeys.size < 2 || merge.isRunning) return
        mutableState.value = mutableState.value.copy(merge = merge.copy(isRunning = true, progress = "正在复制 Mod 并执行 ID 重映射…"))
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    com.sultansgame.modmanager.merge.MergeWorkspace(mergeRoot).use { workspace ->
                        val inputs = workspace.copyInputs(merge.selectedCacheKeys.map { File(File(getApplication<Application>().filesDir, "mod-cache"), it) })
                        val output = workspace.outputDirectory()
                        mergeEngine.merge(inputs, selection, output, merge.selectedCacheKeys.mapNotNull { key -> mutableState.value.cachedMods.firstOrNull { it.cacheKey == key }?.displayName })
                        privateModCache.importDirectory(output, com.sultansgame.modmanager.model.CacheSource.Generated, merge.resultDisplayName)
                    }
                }
            }
            result.onSuccess { cached ->
                val all = (mutableState.value.cachedMods + cached).distinctBy { it.cacheKey }
                deploymentPlan.ensureSynced(all)
                deploymentPlan.setSyncedToGame(cached.cacheKey, true, all)
                mutableState.value = mutableState.value.copy(
                    cachedMods = all,
                    merge = mutableState.value.merge.copy(isRunning = false, progress = null, resultCacheKey = cached.cacheKey, awaitingSyncDecision = true),
                    feedback = FeedbackMessage("合并 Mod 已加入 Manager 缓存。"),
                )
                refreshGameModSyncItems()
                processPendingGameModSyncOperations()
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(merge = mutableState.value.merge.copy(isRunning = false, progress = null), feedback = FeedbackMessage("合并失败：${error.message ?: "未知错误"}", isError = true))
            }
        }
    }

    fun keepOriginalSync() {
        mutableState.value = mutableState.value.copy(merge = mutableState.value.merge.copy(awaitingSyncDecision = false))
    }

    fun stopOriginalSync() {
        val keys = mutableState.value.merge.selectedCacheKeys
        val cached = mutableState.value.cachedMods
        keys.forEach { deploymentPlan.setSyncedToGame(it, false, cached) }
        mutableState.value = mutableState.value.copy(merge = mutableState.value.merge.copy(awaitingSyncDecision = false))
        processPendingGameModSyncOperations()
    }

    fun refreshGameModSync() {
        if (gameModSyncJob?.isActive == true || mutableState.value.gameModSyncInProgress) return
        gameModSyncJob = viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { loaderBridge.listMods() }
            mutableState.value = mutableState.value.copy(gameModSync = status)
            if (status.isReady) processPendingGameModSyncOperations()
        }
    }

    fun setModSyncedToGame(cacheKey: String, syncedToGame: Boolean) {
        if (mutableState.value.cachedModDeletionInProgress) return
        deploymentPlan.setSyncedToGame(cacheKey, syncedToGame, mutableState.value.cachedMods)
        refreshGameModSyncItems()
        processPendingGameModSyncOperations()
    }

    fun deleteCachedMod(cacheKey: String) {
        if (mutableState.value.cachedModDeletionInProgress) return
        val cachedModsBeforeDeletion = mutableState.value.cachedMods
        val target = cachedModsBeforeDeletion.firstOrNull { it.cacheKey == cacheKey } ?: return
        mutableState.value = mutableState.value.copy(cachedModDeletionInProgress = true)
        var removalQueued = false
        viewModelScope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { privateModCache.deleteCached(target.cacheKey) }) {
                    CachedModDeletionResult.Deleted,
                    CachedModDeletionResult.NotFound -> {
                        deploymentPlan.remove(target.cacheKey, cachedModsBeforeDeletion)
                        removalQueued = true
                        val remainingCachedMods = cachedModsBeforeDeletion.filterNot { it.cacheKey == target.cacheKey }
                        mutableState.value = mutableState.value.copy(
                            cachedMods = remainingCachedMods,
                            feedback = FeedbackMessage(
                                "已删除 ${target.displayName} 的 Manager 私有缓存；游戏目录中的对应 Mod 将自动移除。",
                            ),
                        )
                        refreshGameModSyncItems()
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
                if (removalQueued) processPendingGameModSyncOperations()
            }
        }
    }

    private fun processPendingGameModSyncOperations() {
        if (mutableState.value.gameModSyncInProgress || mutableState.value.cachedModDeletionInProgress) return
        val pending = deploymentPlan.pendingOperations()
        if (pending.isEmpty()) return
        mutableState.value = mutableState.value.copy(
            pendingGameModSyncOperations = pending,
            gameModSyncInProgress = true,
        )
        viewModelScope.launch {
            try {
                for (operation in pending) {
                    val item = deploymentPlan.entries(mutableState.value.cachedMods)
                        .firstOrNull { it.cacheKey == operation.cacheKey }
                    val status = withContext(Dispatchers.IO) {
                        when (operation.type) {
                            com.sultansgame.modmanager.model.GameModSyncOperationType.Sync -> {
                                if (item == null) loaderBridge.removeManagedMod(operation.cacheKey) else loaderBridge.syncMod(item)
                            }
                            com.sultansgame.modmanager.model.GameModSyncOperationType.Remove -> loaderBridge.removeManagedMod(operation.cacheKey)
                        }
                    }
                    mutableState.value = mutableState.value.copy(gameModSync = status)
                    if (!status.isReady) {
                        mutableState.value = mutableState.value.copy(
                            feedback = FeedbackMessage(status.reason ?: "等待同步到游戏。", isError = status.availability != com.sultansgame.modmanager.model.GameModSyncAvailability.ActivationRequired),
                        )
                        break
                    }
                    deploymentPlan.complete(operation)
                }
                refreshGameModSyncItems()
                val status = withContext(Dispatchers.IO) { loaderBridge.listMods() }
                mutableState.value = mutableState.value.copy(gameModSync = status)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("同步给游戏失败：${error.message ?: "请重试。"}", isError = true),
                )
            } finally {
                mutableState.value = mutableState.value.copy(gameModSyncInProgress = false)
                refreshGameModSyncItems()
            }
        }
    }

    fun launchGameForModSync() {
        val intent = getApplication<Application>().packageManager
            .getLaunchIntentForPackage("com.gametree.sultan.pd")
        if (intent == null) {
            mutableState.value = mutableState.value.copy(
                feedback = FeedbackMessage("未找到游戏启动入口；请重新修补并安装匹配的游戏版本。", isError = true),
            )
            return
        }
        uiEventChannel.trySend(ManagerUiEvent.LaunchGameForModSync(intent))
    }

    private fun refreshGameModSyncItems() {
        val cachedMods = mutableState.value.cachedMods
        mutableState.value = mutableState.value.copy(
            gameModSyncItems = deploymentPlan.entries(cachedMods),
            pendingGameModSyncOperations = deploymentPlan.pendingOperations(),
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
                        com.sultansgame.modmanager.model.PatchInstallMode.FreshInstall,
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

    private fun checkForUpdateAtStartup() {
        if (updateCheckJob?.isActive == true) return
        updateCheckJob = viewModelScope.launch {
            val result = withTimeoutOrNull(10_000) {
                withContext(Dispatchers.IO) { updateChecker.check(BuildConfig.VERSION_NAME) }
            } ?: return@launch
            if (!updateCheckEnabled || result !is UpdateCheckResult.UpdateAvailable) return@launch
            mutableState.value = mutableState.value.copy(availableUpdate = result.update)
        }
    }

    fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        updateCheckEnabled = enabled
        if (!enabled) {
            updateCheckJob?.cancel()
            mutableState.value = mutableState.value.copy(
                autoUpdateCheckEnabled = false,
                availableUpdate = null,
            )
        } else {
            mutableState.value = mutableState.value.copy(autoUpdateCheckEnabled = true)
        }
        viewModelScope.launch { updateCheckSettings.setAutoCheckEnabled(enabled) }
    }

    fun dismissAvailableUpdate() {
        mutableState.value = mutableState.value.copy(availableUpdate = null)
    }

    fun openAvailableUpdate() {
        val update = mutableState.value.availableUpdate ?: return
        if (!isAllowedReleasePageUrl(update.releaseUrl)) return
        uiEventChannel.trySend(ManagerUiEvent.OpenExternalUrl(update.releaseUrl))
    }

    fun onExternalUrlOpenFailed() {
        mutableState.value = mutableState.value.copy(
            feedback = FeedbackMessage("未找到可打开下载页面的浏览器。", isError = true),
        )
    }

    fun clearFeedback() {
        mutableState.value = mutableState.value.copy(feedback = null)
    }

    fun reportExternalImportError(reason: String) {
        mutableState.value = mutableState.value.copy(feedback = FeedbackMessage(reason, isError = true))
    }

    fun clearModCache() {
        if (mutableState.value.cachedModDeletionInProgress) return
        val cachedMods = mutableState.value.cachedMods
        mutableState.value = mutableState.value.copy(cachedModDeletionInProgress = true)
        var removalsQueued = false
        viewModelScope.launch {
            try {
                cachedMods.forEach { cached ->
                    when (val result = withContext(Dispatchers.IO) { privateModCache.deleteCached(cached.cacheKey) }) {
                        CachedModDeletionResult.Deleted,
                        CachedModDeletionResult.NotFound -> Unit
                        is CachedModDeletionResult.Rejected -> error("无法删除 ${cached.displayName}：${result.reason}")
                        is CachedModDeletionResult.Failed -> error("无法删除 ${cached.displayName}：${result.reason}")
                    }
                }
                cachedMods.forEach { deploymentPlan.remove(it.cacheKey, cachedMods) }
                removalsQueued = cachedMods.isNotEmpty()
                mutableState.value = mutableState.value.copy(
                    cachedMods = emptyList(),
                    feedback = FeedbackMessage("已清空 Manager 私有 Mod 缓存；游戏目录中的对应 Mod 将自动移除。"),
                )
                refreshGameModSyncItems()
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("清理 Manager 私有 Mod 缓存失败：${error.message ?: "请重试。"}", isError = true),
                )
            } finally {
                mutableState.value = mutableState.value.copy(cachedModDeletionInProgress = false)
                if (removalsQueued) processPendingGameModSyncOperations()
            }
        }
    }

    fun requestPatchCleanupConfirmation() {
        val candidate = mutableState.value.patchCleanup ?: return
        if (mutableState.value.patchCleanupInProgress) return
        mutableState.value = mutableState.value.copy(patchCleanupConfirmation = candidate)
    }

    fun dismissPatchCleanupConfirmation() {
        mutableState.value = mutableState.value.copy(patchCleanupConfirmation = null)
    }

    fun confirmPatchCleanup() {
        if (mutableState.value.patchCleanup == null || mutableState.value.patchCleanupInProgress) return
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
        if (mutableState.value.pendingExternalZip != null || mutableState.value.zipImportInProgress) {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("请先处理当前待导入的 ZIP 文件。", isError = true))
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("正在安全接收 ZIP 文件…"))
            runCatching { withContext(Dispatchers.IO) { externalZipInbox.receive(uri) } }
                .onSuccess { request -> inspectPendingZip(request, showExternalConfirmation = false) }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        feedback = FeedbackMessage("无法接收 ZIP：${error.message ?: "请重试"}", isError = true),
                    )
                }
        }
    }

    fun receiveExternalZip(uri: Uri) {
        if (mutableState.value.pendingExternalZip != null || mutableState.value.zipImportInProgress) {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("请先处理当前待导入的外部 ZIP 文件。", isError = true))
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("正在安全接收外部 ZIP 文件…"))
            runCatching { withContext(Dispatchers.IO) { externalZipInbox.receive(uri) } }
                .onSuccess { request -> inspectPendingZip(request, showExternalConfirmation = true) }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        feedback = FeedbackMessage("无法接收外部 ZIP：${error.message ?: "请重试"}", isError = true),
                    )
                }
        }
    }

    private fun inspectPendingZip(request: com.sultansgame.modmanager.platform.saf.ExternalZipImportRequest, showExternalConfirmation: Boolean) {
        viewModelScope.launch {
            try {
                val inspection = withContext(Dispatchers.IO) { zipImporter.inspect(externalZipInbox.fileFor(request)) }
                mutableState.value = mutableState.value.copy(
                    pendingExternalZip = request,
                    pendingZipPassword = inspection.passwordRequired,
                    feedback = null,
                )
                if (!inspection.passwordRequired && !showExternalConfirmation) {
                    confirmExternalZipImport()
                }
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { externalZipInbox.discard(request) }
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("ZIP 导入失败：${error.message ?: "无法验证内容"}", isError = true),
                )
            }
        }
    }

    fun confirmExternalZipImport() {
        val request = mutableState.value.pendingExternalZip ?: return
        if (mutableState.value.pendingZipPassword) return
        mutableState.value = mutableState.value.copy(zipImportInProgress = true, feedback = FeedbackMessage("正在校验并导入 ${request.displayName}…"))
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    zipImporter.importZip(
                        externalZipInbox.fileFor(request),
                        archiveDisplayName = request.displayName,
                    )
                }
                updateImportedMods(imported)
                clearPendingZip(request)
            } catch (error: CancellationException) {
                clearPendingZip(request)
                throw error
            } catch (error: Exception) {
                clearPendingZip(request)
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("ZIP 导入失败：${error.message ?: "无法验证内容"}", isError = true),
                )
            } finally {
                mutableState.value = mutableState.value.copy(zipImportInProgress = false)
            }
        }
    }

    fun submitZipPassword(password: CharArray) {
        val request = mutableState.value.pendingExternalZip ?: run {
            password.fill('\u0000')
            return
        }
        if (!mutableState.value.pendingZipPassword || mutableState.value.zipImportInProgress) {
            password.fill('\u0000')
            return
        }
        mutableState.value = mutableState.value.copy(zipImportInProgress = true, feedback = null)
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    zipImporter.importZip(
                        externalZipInbox.fileFor(request),
                        password,
                        archiveDisplayName = request.displayName,
                    )
                }
                updateImportedMods(imported)
                clearPendingZip(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: com.sultansgame.modmanager.platform.saf.ZipImportException.InvalidPasswordOrEncryptedData) {
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("密码错误，或 ZIP 加密内容已损坏，请重试。", isError = true),
                )
            } catch (error: Exception) {
                clearPendingZip(request)
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("ZIP 导入失败：${error.message ?: "无法验证内容"}", isError = true),
                )
            } finally {
                password.fill('\u0000')
                mutableState.value = mutableState.value.copy(zipImportInProgress = false)
            }
        }
    }

    private suspend fun clearPendingZip(request: com.sultansgame.modmanager.platform.saf.ExternalZipImportRequest) {
        withContext(Dispatchers.IO) { externalZipInbox.discard(request) }
        mutableState.value = mutableState.value.copy(pendingExternalZip = null, pendingZipPassword = false)
    }

    fun cancelExternalZipImport() {
        val request = mutableState.value.pendingExternalZip ?: return
        if (mutableState.value.zipImportInProgress) return
        mutableState.value = mutableState.value.copy(pendingExternalZip = null, pendingZipPassword = false)
        viewModelScope.launch(Dispatchers.IO) { externalZipInbox.discard(request) }
    }

    private fun updateImportedMods(imported: List<com.sultansgame.modmanager.model.CachedMod>) {
        val cachedMods = (mutableState.value.cachedMods + imported).distinctBy { it.cacheKey }
        deploymentPlan.ensureSynced(cachedMods)
        imported.forEach { deploymentPlan.setSyncedToGame(it.cacheKey, true, cachedMods) }
        mutableState.value = mutableState.value.copy(
            cachedMods = cachedMods,
            feedback = FeedbackMessage(
                "已安全缓存 ${imported.size} 个 Mod，并将自动同步到游戏。请在游戏内 Mod 面板管理加载、开关和排序。",
            ),
        )
        refreshGameModSyncItems()
        processPendingGameModSyncOperations()
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
                    updateImportedMods(listOf(cached))
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
                orchestrator.submit(
                    selected.source,
                    selected.extracted,
                    review.confirmation,
                    selected.trustedDeviceCertificateSha256,
                )
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
                PatchUiState.ReadyToInstall(
                    transactionId,
                    "已获得安装授权；请确认后安装已准备的修补工件。",
                    com.sultansgame.modmanager.model.PatchInstallMode.FreshInstall,
                )
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
                    val trustedDeviceCertificateSha256 = deviceSigningKeyStore.certificateSha256()
                    val classification = profileRegistry.classify(
                        source,
                        extracted,
                        trustedDeviceCertificateSha256 = trustedDeviceCertificateSha256,
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
                    selectedPatchInput = SelectedPatchInput(
                        source = source,
                        extracted = extracted,
                        uiModel = input,
                        trustedDeviceCertificateSha256 = trustedDeviceCertificateSha256,
                    )
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
            is PatchOrchestrationResult.ReadyToInstall -> PatchUiState.ReadyToInstall(
                transactionId = result.transactionId,
                summary = result.summary,
                installMode = result.installMode,
            )
            is PatchOrchestrationResult.NeedsGameUninstall -> {
                val currentGameState = withContext(Dispatchers.IO) { gameProbe.probe() }
                mutableState.value = mutableState.value.copy(gameProbeResult = currentGameState)
                when (currentGameState) {
                    GameProbeResult.NotInstalled -> PatchUiState.ReadyToInstall(
                        result.transactionId,
                        "已确认原版游戏未安装；可安装已准备的修补工件。",
                        com.sultansgame.modmanager.model.PatchInstallMode.FreshInstall,
                    )
                    else -> PatchUiState.AwaitingOriginalUninstall(
                        transactionId = result.transactionId,
                        gameState = currentGameState,
                        summary = result.reason,
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

    private fun reservedPatchWorkspaceIds(): Set<String> = buildSet {
        if (mutableState.value.patch is PatchUiState.Review || mutableState.value.patch is PatchUiState.Preparing) {
            selectedPatchInput?.extracted?.transactionId?.let(::add)
        }
        when (val patch = mutableState.value.patch) {
            is PatchUiState.AwaitingSystemInstall -> add(patch.transactionId)
            is PatchUiState.SubmittingInstall -> add(patch.transactionId)
            else -> Unit
        }
        mutableState.value.apksExport.transactionIdOrNull()?.let(::add)
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
            summary = "发现已准备的修补 APK；继续前会校验工件、设备签名身份和当前安装 split。",
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
