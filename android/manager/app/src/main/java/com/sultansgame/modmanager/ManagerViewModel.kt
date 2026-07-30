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
import com.sultansgame.modmanager.platform.patch.AndroidKeystoreApkSigner
import com.sultansgame.modmanager.platform.patch.AndroidLoaderSplitArtifactFactory
import com.sultansgame.modmanager.platform.patch.DeviceSigningKeyStore
import com.sultansgame.modmanager.platform.patch.GameProfileRegistry
import com.sultansgame.modmanager.platform.patch.InstalledApkExtractor
import com.sultansgame.modmanager.platform.patch.PackageInstallerGateway
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
import com.sultansgame.modmanager.platform.game.AndroidModStorageLoaderBridge
import com.sultansgame.modmanager.platform.game.GameProbeResult
import com.sultansgame.modmanager.platform.game.PackageManagerGameProbe
import com.sultansgame.modmanager.platform.saf.ZipModImporter
import com.sultansgame.modmanager.platform.storage.AndroidPrivateModCache
import com.sultansgame.modmanager.platform.storage.DeploymentPlanStore
import com.sultansgame.modmanager.platform.workshop.SteamPublicMetadataTransport
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
    data class OpenGameUninstall(val transactionId: String) : ManagerUiEvent
    data class OpenUnknownSourcesSettings(val intent: android.content.Intent) : ManagerUiEvent
    data class ConfirmPackageInstall(val intent: android.content.Intent) : ManagerUiEvent
    data class CreateApksExport(val transactionId: String, val suggestedName: String) : ManagerUiEvent
}

class ManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val privateModCache = AndroidPrivateModCache(File(application.filesDir, "mod-cache"))
    private val deploymentPlan = DeploymentPlanStore(application)
    private val zipImporter = ZipModImporter(application, privateModCache)
    private val artifactImporter = WorkshopArtifactImporter(application, privateModCache, zipImporter)
    private val taskStore = WorkshopTaskStore(application)
    private val downloadScheduler = WorkshopDownloadScheduler(application)
    private val steamAuthProvider = SteamCmAuthProvider(application)
    private val steamAuth: SteamAuthProvider = steamAuthProvider
    private val workshopProvider = SteamPublicWorkshopProvider(SteamPublicMetadataTransport())
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
    private var workshopBrowseGeneration = 0L

    init {
        privateModCache.recoverInterruptedImports()
        val cachedMods = privateModCache.listCached()
        val pendingPatch = transactions.latestResumable()
        mutableState.value = mutableState.value.copy(
            cachedMods = cachedMods,
            deploymentPlan = deploymentPlan.entries(cachedMods),
            downloadTasks = taskStore.tasks.value,
            deviceSigningKeyState = deviceSigningKeyStore.state(),
            patch = pendingPatch?.let(::restorePatchUiState) ?: PatchUiState.ChooseSource,
        )
        refreshGame()
        refreshGameModStorage()
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
        viewModelScope.launch {
            val storage = withContext(Dispatchers.IO) { loaderBridge.storageStatus() }
            mutableState.value = mutableState.value.copy(gameModStorage = storage)
        }
    }

    fun setModEnabled(cacheKey: String, enabled: Boolean) {
        deploymentPlan.setEnabled(cacheKey, enabled, mutableState.value.cachedMods)
        refreshDeploymentPlan()
    }

    fun moveMod(cacheKey: String, delta: Int) {
        deploymentPlan.move(cacheKey, delta, mutableState.value.cachedMods)
        refreshDeploymentPlan()
    }

    fun syncMods(allowExternalReplacement: Boolean) {
        viewModelScope.launch {
            val snapshot = deploymentPlan.snapshot(mutableState.value.cachedMods, allowExternalReplacement)
            mutableState.value = mutableState.value.copy(deploymentInProgress = true, feedback = null)
            val result = withContext(Dispatchers.IO) { loaderBridge.requestApply(ApplyRequest(snapshot)) }
            when (result) {
                is ApplyResult.Applied -> mutableState.value = mutableState.value.copy(
                    deploymentInProgress = false,
                    gameModStorage = result.result.status,
                    feedback = FeedbackMessage("已同步 ${snapshot.enabledEntries.size} 个启用 Mod；请退出并冷启动游戏。"),
                )
                is ApplyResult.Rejected -> mutableState.value = mutableState.value.copy(
                    deploymentInProgress = false,
                    gameModStorage = result.status,
                    feedback = FeedbackMessage(result.status.reason ?: "同步到游戏失败。", isError = true),
                )
            }
        }
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

    fun importZip(uri: Uri) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("正在校验并导入 ZIP Mod…"))
            runCatching { withContext(Dispatchers.IO) { zipImporter.importZip(uri) } }
                .onSuccess { imported ->
                    val cachedMods = (mutableState.value.cachedMods + imported).distinctBy { it.cacheKey }
                    mutableState.value = mutableState.value.copy(
                        cachedMods = cachedMods,
                        deploymentPlan = deploymentPlan.entries(cachedMods),
                        feedback = FeedbackMessage(
                            "已安全缓存 ${imported.size} 个 Mod：${imported.joinToString { it.displayName }}；可在 Mod 页面启用并同步到游戏。",
                        ),
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        feedback = FeedbackMessage("ZIP 导入失败：${error.message ?: "无法验证内容"}", isError = true),
                    )
                }
        }
    }

    fun beginSteamLogin(username: String, password: String) {
        viewModelScope.launch {
            handleAuthResult(steamAuth.beginLogin(SteamCredentials(username, password)))
        }
    }

    fun submitSteamGuard(code: String) {
        viewModelScope.launch { handleAuthResult(steamAuth.submitSteamGuard(code)) }
    }

    fun logoutSteam() {
        viewModelScope.launch { handleAuthResult(steamAuth.logout()) }
    }

    fun browseWorkshop(query: com.sultansgame.modmanager.model.WorkshopBrowseQuery = com.sultansgame.modmanager.model.WorkshopBrowseQuery()) {
        val normalizedQuery = query.normalized()
        workshopBrowseJob?.cancel()
        val generation = ++workshopBrowseGeneration
        workshopBrowseJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                workshopBrowse = mutableState.value.workshopBrowse.copy(
                    query = normalizedQuery,
                    isLoading = true,
                    error = null,
                ),
            )
            runCatching { withContext(Dispatchers.IO) { communityWorkshopBrowser.browse(normalizedQuery) } }
                .onSuccess { page ->
                    if (generation != workshopBrowseGeneration) return@onSuccess
                    val previous = mutableState.value.workshopBrowse
                    val append = normalizedQuery.page > 1 &&
                        previous.query.copy(page = 1) == normalizedQuery.copy(page = 1)
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
                        ),
                    )
                }
                .onFailure { error ->
                    if (generation != workshopBrowseGeneration || error is kotlinx.coroutines.CancellationException) return@onFailure
                    val current = mutableState.value.workshopBrowse
                    mutableState.value = mutableState.value.copy(
                        workshopBrowse = current.copy(
                            query = normalizedQuery,
                            isLoading = false,
                            error = error.message ?: "无法读取 Steam 创意工坊。",
                        ),
                    )
                }
        }
    }
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
        val accessMode = WorkshopAccessMode.Anonymous
            val lookup = withContext(Dispatchers.IO) {
                workshopProvider.getItem(SULTANS_GAME_APP_ID, id, accessMode)
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
        val accessMode = WorkshopAccessMode.Anonymous
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            appId = SULTANS_GAME_APP_ID,
            publishedFileId = item.publishedFileId,
            accessMode = accessMode,
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
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("已将 ${item.title} 加入下载队列。"))
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
        val current = mutableState.value.patch
        if (current !is PatchUiState.AwaitingOriginalUninstall &&
            current !is PatchUiState.ReadyToInstall &&
            current !is PatchUiState.AwaitingInstallPermission
        ) return
        val suggestedName = "sultans-game-patched-${transactionId.take(8)}.apks"
        uiEventChannel.trySend(ManagerUiEvent.CreateApksExport(transactionId, suggestedName))
    }

    fun writePreparedApks(transactionId: String, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri)) {
                        "无法写入所选导出位置。"
                    }.use { output ->
                        ApksExporter(transactions).export(transactionId, output)
                    }
                }
            }.onSuccess {
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("已导出修补 APKS；请使用支持 APKS 的安装工具安装。"),
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("导出 APKS 失败：${error.message ?: "无法写入文件"}", isError = true),
                )
            }
        }
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
            deviceSigningKeyState = deviceSigningKeyStore.state(),
            feedback = (result as? PatchOrchestrationResult.Failed)?.let { FeedbackMessage("迁移失败：${it.reason}", isError = true) }
                ?: mutableState.value.feedback,
        )
    }

    private companion object {
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
