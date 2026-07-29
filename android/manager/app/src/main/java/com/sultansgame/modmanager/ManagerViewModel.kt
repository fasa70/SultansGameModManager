package com.sultansgame.modmanager

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sultansgame.modmanager.bridge.ApplyRequest
import com.sultansgame.modmanager.bridge.ApplyResult
import com.sultansgame.modmanager.bridge.LoaderBridge
import com.sultansgame.modmanager.bridge.UnavailableLoaderBridge
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.PatchConfirmation
import com.sultansgame.modmanager.model.PatchSource
import com.sultansgame.modmanager.model.PatchStage
import com.sultansgame.modmanager.platform.patch.AndroidApkArchiveInspector
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
import com.sultansgame.modmanager.platform.workshop.SultanWorkshopCatalog
import com.sultansgame.modmanager.platform.workshop.WorkshopArtifactImporter
import com.sultansgame.modmanager.platform.workshop.WorkshopDownloadScheduler
import com.sultansgame.modmanager.platform.workshop.WorkshopTaskStore
import com.sultansgame.modmanager.workshop.SteamPublicWorkshopProvider
import com.sultansgame.modmanager.workshop.WorkshopLookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val privateModCache = AndroidPrivateModCache(File(application.filesDir, "mod-cache"))
    private val deploymentPlan = DeploymentPlanStore(application)
    private val zipImporter = ZipModImporter(application, privateModCache)
    private val artifactImporter = WorkshopArtifactImporter(application, privateModCache, zipImporter)
    private val taskStore = WorkshopTaskStore(application)
    private val downloadScheduler = WorkshopDownloadScheduler(application)
    private val steamAuthProvider = SteamCmAuthProvider(application)
    private val steamAuth: SteamAuthProvider = steamAuthProvider
    private val catalog = SultanWorkshopCatalog()
    private val gameProbe = PackageManagerGameProbe(application)
    private val deviceSigningKeyStore = DeviceSigningKeyStore(application)
    private val archiveInspector = AndroidApkArchiveInspector(application)
    private val profileRegistry = GameProfileRegistry()
    private val apkExtractor = InstalledApkExtractor(application)
    private val transactions = PatchTransactionStore(application)
    private val orchestrator = PatchOrchestrator(
        keyStore = deviceSigningKeyStore,
        profileRegistry = profileRegistry,
        signer = AndroidKeystoreApkSigner(),
        installer = PackageInstallerGateway(application),
        transactions = transactions,
        archiveInspector = archiveInspector,
        gameProbe = gameProbe,
        splitFactoryForNativeDigest = { nativeDigest ->
            AndroidLoaderSplitArtifactFactory(application, nativeDigest)
        },
    )
    private val workshopProvider = SteamPublicWorkshopProvider(SteamPublicMetadataTransport())
    private val loaderBridge: LoaderBridge = AndroidModStorageLoaderBridge(application, File(application.filesDir, "mod-cache"))
    private val legalNotice = LegalNoticeRepository(application)

    private val mutableState = MutableStateFlow(ManagerUiState())
    val state: StateFlow<ManagerUiState> = mutableState.asStateFlow()

    init {
        privateModCache.recoverInterruptedImports()
        val cachedMods = privateModCache.listCached()
        mutableState.value = mutableState.value.copy(
            cachedMods = cachedMods,
            deploymentPlan = deploymentPlan.entries(cachedMods),
            downloadTasks = taskStore.tasks.value,
            deviceSigningKeyState = deviceSigningKeyStore.state(),
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
            val classification = (result as? com.sultansgame.modmanager.platform.game.GameProbeResult.Found)
                ?.snapshot
                ?.let { snapshot ->
                    GameProfileRegistry().classify(
                        PatchSource.InstalledGame,
                        com.sultansgame.modmanager.model.ApkInspection(
                            sourceLabel = "已安装游戏",
                            packageName = snapshot.packageName,
                            versionCode = snapshot.versionCode,
                            versionName = snapshot.versionName,
                            splitName = null,
                            supportedAbis = setOf("arm64-v8a"),
                            signerDigestsSha256 = snapshot.signerDigestsSha256,
                            entryCount = 0,
                            sizeBytes = 0,
                            warnings = emptyList(),
                        ),
                        trustedDeviceCertificateSha256 = deviceSigningKeyStore.certificateSha256(),
                    )
                }
            mutableState.value = mutableState.value.copy(
                gameProbeResult = result,
                patchClassification = classification,
                patchStatus = classification?.compatibility?.reasons?.joinToString(),
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
                .onSuccess { cached ->
                    val cachedMods = (mutableState.value.cachedMods + cached).distinctBy { it.cacheKey }
                    mutableState.value = mutableState.value.copy(
                        cachedMods = cachedMods,
                        deploymentPlan = deploymentPlan.entries(cachedMods),
                        feedback = FeedbackMessage("已安全缓存 ${cached.displayName}；可在 Mod 页面启用并同步到游戏。"),
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

    fun searchWorkshop(query: String, page: Int = 1) {
        val account = steamAuthProvider.activeSession()
        if (account == null) {
            mutableState.value = mutableState.value.copy(
                workshopSearch = WorkshopSearchUiState.Error("搜索需要登录 Steam 账号。"),
            )
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(workshopSearch = WorkshopSearchUiState.Loading)
            runCatching { withContext(Dispatchers.IO) { catalog.search(account, query, page) } }
                .onSuccess { result ->
                    mutableState.value = mutableState.value.copy(
                        workshopSearch = WorkshopSearchUiState.Results(result.items, result.page, result.hasNextPage),
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        workshopSearch = WorkshopSearchUiState.Error(error.message ?: "无法搜索 Steam 创意工坊。"),
                    )
                }
        }
    }

    fun lookupWorkshop(rawId: String) {
        val id = PublishedFileId.parse(rawId)
        if (id == null) {
            mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("PublishedFileId 必须是正整数。", isError = true))
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(workshop = WorkshopUiState.Loading)
            val accessMode = catalog.accessMode(steamAuthProvider.activeSession())
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
        val accessMode = catalog.accessMode(steamAuthProvider.activeSession())
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            appId = SULTANS_GAME_APP_ID,
            publishedFileId = item.publishedFileId,
            accessMode = accessMode,
            stage = DownloadStage.Queued,
            title = item.title,
            totalBytes = item.declaredSizeBytes,
        )
        taskStore.upsert(task)
        downloadScheduler.enqueue(task)
        mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("已将 ${item.title} 加入下载队列。"))
    }

    fun retryWorkshopDownload(taskId: String) {
        val task = taskStore.get(taskId) ?: return
        taskStore.update(taskId) { it.copy(stage = DownloadStage.Queued, failure = null) }
        downloadScheduler.enqueue(task)
    }

    fun cancelWorkshopDownload(taskId: String) {
        downloadScheduler.cancel(taskId)
        taskStore.update(taskId) { it.copy(stage = DownloadStage.Cancelled, failure = DownloadFailureCode.Cancelled) }
    }

    fun confirmWorkshopImport(taskId: String) {
        val task = taskStore.get(taskId)?.takeIf { it.stage == DownloadStage.AwaitingImportConfirmation } ?: return
        viewModelScope.launch {
            taskStore.update(taskId) { it.copy(stage = DownloadStage.Importing) }
            runCatching { withContext(Dispatchers.IO) { artifactImporter.importConfirmed(task) } }
                .onSuccess { cached ->
                    taskStore.update(taskId) { it.copy(stage = DownloadStage.Imported, failure = null) }
                    mutableState.value = mutableState.value.copy(
                        cachedMods = (mutableState.value.cachedMods + cached).distinctBy { it.cacheKey },
                        feedback = FeedbackMessage("已安全缓存 ${cached.displayName}；尚未同步到游戏。"),
                    )
                }
                .onFailure { error ->
                    taskStore.update(taskId) { it.copy(stage = DownloadStage.AwaitingImportConfirmation, failure = DownloadFailureCode.ImportFailed) }
                    mutableState.value = mutableState.value.copy(
                        feedback = FeedbackMessage("下载内容未能导入：${error.message ?: "无法验证内容"}", isError = true),
                    )
                }
        }
    }

    fun discardWorkshopArtifact(taskId: String) {
        val task = taskStore.get(taskId) ?: return
        artifactImporter.discard(task)
        taskStore.update(taskId) { it.copy(stage = DownloadStage.Cancelled, failure = DownloadFailureCode.Cancelled) }
    }

    fun updatePatchConfirmation(confirmation: PatchConfirmation) {
        mutableState.value = mutableState.value.copy(patchConfirmation = confirmation)
    }

    fun beginPatching() {
        val current = mutableState.value
        if (current.patchInProgress || current.gameProbeResult !is GameProbeResult.Found) return
        val confirmation = current.patchConfirmation
        val classification = current.patchClassification ?: return
        if (!confirmation.permits(classification.mode)) return

        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(patchInProgress = true, patchStatus = "正在提取并重签游戏 APK…", patchStage = PatchStage.PreparingArtifacts)
            val extracted = withContext(Dispatchers.IO) {
                runCatching { apkExtractor.extract("com.gametree.sultan.pd") }
            }.getOrElse { error ->
                mutableState.value = mutableState.value.copy(
                    patchInProgress = false,
                    patchStatus = "提取游戏 APK 失败：${error.message}",
                    feedback = FeedbackMessage("提取游戏 APK 失败：${error.message}", isError = true),
                )
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                orchestrator.submit(PatchSource.InstalledGame, extracted, confirmation)
            }
            applyOrchestrationResult(result)
        }
    }

    private fun handleInstallResult(intent: android.content.Intent) {
        val result = orchestrator.handleInstallResult(intent) ?: return
        applyOrchestrationResult(result)
    }

    private fun applyOrchestrationResult(result: PatchOrchestrationResult) {
        when (result) {
            is PatchOrchestrationResult.AwaitingConfirmation -> {
                mutableState.value = mutableState.value.copy(
                    patchStatus = result.reason,
                    patchStage = PatchStage.AwaitingConfirmation,
                )
            }
            is PatchOrchestrationResult.AwaitingSystemInstall -> {
                mutableState.value = mutableState.value.copy(
                    patchInProgress = false,
                    patchStage = PatchStage.AwaitingSystemInstall,
                    patchStatus = "等待系统完成安装…",
                )
            }
            is PatchOrchestrationResult.NeedsUserAction -> {
                mutableState.value = mutableState.value.copy(
                    patchStatus = "需要你在系统界面中确认安装。",
                )
            }
            is PatchOrchestrationResult.AwaitingVerification -> {
                mutableState.value = mutableState.value.copy(
                    patchStage = PatchStage.VerifyingInstall,
                    patchStatus = "正在验证安装结果…",
                )
            }
            is PatchOrchestrationResult.Completed -> {
                mutableState.value = mutableState.value.copy(
                    patchInProgress = false,
                    patchStage = PatchStage.Completed,
                    patchStatus = "迁移完成。请冷启动游戏验证。",
                    feedback = FeedbackMessage("迁移完成。请退出游戏后重新启动以加载 Mod 支持。"),
                )
                refreshGame()
            }
            is PatchOrchestrationResult.Failed -> {
                mutableState.value = mutableState.value.copy(
                    patchInProgress = false,
                    patchStage = PatchStage.Failed,
                    patchStatus = result.reason,
                    feedback = FeedbackMessage("迁移失败：${result.reason}", isError = true),
                )
            }
        }
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
