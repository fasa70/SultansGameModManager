package com.sultansgame.modmanager

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sultansgame.modmanager.bridge.LoaderBridge
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.GameSaveAvailability
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
import com.sultansgame.modmanager.platform.saveeditor.SaveArchiveIndex
import com.sultansgame.modmanager.platform.saveeditor.SaveBackupEntry
import com.sultansgame.modmanager.platform.saveeditor.SaveBackupStore
import com.sultansgame.modmanager.platform.saveeditor.SaveEditorWebEvent
import com.sultansgame.modmanager.platform.saveeditor.SaveEditorWebViewHolder
import com.sultansgame.modmanager.platform.saf.ExternalZipInbox
import com.sultansgame.modmanager.platform.saf.ZipModImporter
import com.sultansgame.modmanager.platform.export.ModZipExporter
import com.sultansgame.modmanager.platform.storage.AndroidPrivateModCache
import com.sultansgame.modmanager.platform.storage.AndroidStorageSpaceProbe
import com.sultansgame.modmanager.platform.storage.CachedModDeletionResult
import com.sultansgame.modmanager.platform.storage.CachedModRenameResult
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
    data class CreateModExportDocument(val artifactId: String, val suggestedName: String) : ManagerUiEvent
    data class ShareModExport(val artifactId: String, val fileName: String) : ManagerUiEvent
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
    private val workshopVisibilitySettings = WorkshopVisibilitySettingsRepository(application)
    private val updateChecker: UpdateChecker = GitHubReleaseUpdateChecker()
    private val mergeBridge = com.sultansgame.modmanager.platform.merge.ChaquopyMergeBridge(application)
    private val saveBackupStore = SaveBackupStore(File(application.filesDir, "save-backups"))
    /**
     * The save editor's WebView outlives the composition: unsaved edits exist
     * only as JavaScript state, so a per-composition view would discard them on
     * every tab switch and rotation. Destroyed in [onCleared] and on state reset.
     */
    private val saveEditorWeb = SaveEditorWebViewHolder(
        context = application,
        debuggable = BuildConfig.DEBUG,
        onEvent = ::onSaveEditorWebEvent,
    )
    private val mergeRoot = File(application.cacheDir, "mod-merge")
    private val modExportRoot = File(application.cacheDir, "mod-export")
    private val modZipExporter = ModZipExporter(privateModCache, modExportRoot)
    private val mergeCatalogLoad = loadMergeCatalog()
    private val mergeCatalog = mergeCatalogLoad.catalog

    private data class MergeCatalogLoad(
        val catalog: com.sultansgame.modmanager.merge.BaseIdCatalog?,
        val error: String?,
    )

    private fun loadMergeCatalog(): MergeCatalogLoad = try {
        getApplication<Application>().assets.open("merge/base-id-catalog-10005.json").use { input ->
            MergeCatalogLoad(
                catalog = com.sultansgame.modmanager.merge.BaseIdCatalogJsonCodec().decode(
                    input.readBytes().toString(Charsets.UTF_8),
                ),
                error = null,
            )
        }
    } catch (error: Throwable) {
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        Log.e(LOG_TAG, "无法读取内置 ID Catalog: ${error::class.java.name}: $reason", error)
        MergeCatalogLoad(
            catalog = null,
            error = "无法读取内置 ID Catalog（${error::class.java.simpleName}: $reason）",
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
    private var mergePreflightJob: Job? = null
    private var modExportJob: Job? = null
    private var updateCheckJob: Job? = null
    private var saveEditorJob: Job? = null
    private var updateCheckEnabled = false

    init {
        privateModCache.recoverInterruptedImports()
        modZipExporter.cleanupInterrupted()
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
            val enabled = workshopVisibilitySettings.isWorkshopEnabled.first()
            mutableState.value = mutableState.value.copy(showWorkshop = enabled)
        }
        viewModelScope.launch {
            PatchInstallResults.results.collect { intent ->
                handleInstallResult(intent)
            }
        }
    }

    fun openModExport() {
        val current = mutableState.value
        if (current.cachedMods.isEmpty() || current.gameModSyncInProgress || current.cachedModDeletionInProgress) return
        mutableState.value = current.copy(
            modExport = current.modExport.copy(
                isOpen = true,
                selectedCacheKeys = emptyList(),
                settingsAction = null,
                operation = ModExportOperation.Idle,
            ),
        )
    }

    fun closeModExport() {
        if (modExportJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(isOpen = false, settingsAction = null))
    }

    fun toggleModExport(cacheKey: String) {
        if (modExportJob?.isActive == true || cacheKey !in mutableState.value.cachedMods.map { it.cacheKey }) return
        val current = mutableState.value.modExport.selectedCacheKeys
        val next = if (cacheKey in current) current - cacheKey else current + cacheKey
        mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(selectedCacheKeys = next))
    }

    fun setModExportSelection(cacheKeys: List<String>) {
        if (modExportJob?.isActive == true) return
        val valid = mutableState.value.cachedMods.map { it.cacheKey }.toSet()
        mutableState.value = mutableState.value.copy(
            modExport = mutableState.value.modExport.copy(selectedCacheKeys = cacheKeys.filter(valid::contains).distinct()),
        )
    }

    fun selectAllModExport() {
        if (modExportJob?.isActive == true) return
        val keys = mutableState.value.cachedMods.map { it.cacheKey }
        val selected = mutableState.value.modExport.selectedCacheKeys
        val next = if (selected.size == keys.size && selected.toSet() == keys.toSet()) emptyList() else keys
        mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(selectedCacheKeys = next))
    }

    fun requestModExport(action: ModExportAction) {
        val current = mutableState.value
        if (!current.modExport.isOpen || current.modExport.selectedCacheKeys.isEmpty() || modExportJob?.isActive == true) return
        val suggested = if (current.modExport.selectedCacheKeys.size == 1) {
            current.cachedMods.firstOrNull { it.cacheKey == current.modExport.selectedCacheKeys.single() }?.displayName
                ?.let(::safeZipName) ?: "sultans-game-mods.zip"
        } else "sultans-game-mods.zip"
        mutableState.value = current.copy(modExport = current.modExport.copy(settingsAction = action, suggestedFileName = suggested))
    }

    fun cancelModExportSettings() {
        mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(settingsAction = null))
    }

    fun submitModExport(fileName: String, password: CharArray) {
        val current = mutableState.value
        val action = current.modExport.settingsAction
        if (action == null) {
            password.fill('\u0000')
            return
        }
        val safeName = safeZipName(fileName)
        val keys = current.modExport.selectedCacheKeys
        mutableState.value = current.copy(
            modExport = current.modExport.copy(
                settingsAction = null,
                operation = ModExportOperation.Compressing(action, safeName, 0, 0, 0L, 0L),
            ),
        )
        modExportJob?.cancel()
        modExportJob = viewModelScope.launch {
            try {
                val artifact = withContext(Dispatchers.IO) {
                    modZipExporter.export(keys, safeName, password) { progress ->
                        mutableState.value = mutableState.value.copy(
                            modExport = mutableState.value.modExport.copy(
                                operation = ModExportOperation.Compressing(
                                    action,
                                    safeName,
                                    progress.completedFiles,
                                    progress.totalFiles,
                                    progress.writtenBytes,
                                    progress.totalBytes,
                                ),
                            ),
                        )
                    }
                }
                when (action) {
                    ModExportAction.SaveToLocal -> {
                        mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.SelectingDestination(artifact.id, safeName)))
                        if (uiEventChannel.trySend(ManagerUiEvent.CreateModExportDocument(artifact.id, safeName)).isFailure) {
                            artifact.file.delete()
                            mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Idle), feedback = FeedbackMessage("无法打开文件保存位置。", true))
                        }
                    }
                    ModExportAction.Share -> {
                        mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Sharing(artifact.id, safeName)))
                        if (uiEventChannel.trySend(ManagerUiEvent.ShareModExport(artifact.id, safeName)).isFailure) {
                            artifact.file.delete()
                            mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Idle), feedback = FeedbackMessage("无法打开分享面板。", true))
                        }
                    }
                }
            } catch (_: CancellationException) {
                mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Idle))
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Idle), feedback = FeedbackMessage("Mod 导出失败：${error.message ?: "无法生成 ZIP"}", true))
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun modExportArtifactFile(artifactId: String): File =
        File(modExportRoot, artifactId).listFiles()?.singleOrNull { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            ?: File(modExportRoot, "$artifactId.zip")

    fun writeModExport(artifactId: String, uri: Uri?) {
        val operation = mutableState.value.modExport.operation
        if (operation !is ModExportOperation.SelectingDestination || operation.artifactId != artifactId) return
        val artifact = modExportArtifactFile(artifactId)
        if (uri == null) {
            artifact.delete()
            artifact.parentFile?.takeIf { it.name == artifactId }?.deleteRecursively()
            mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Idle))
            return
        }
        val fileName = operation.fileName
        val total = artifact.length()
        mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Writing(artifactId, fileName, 0L, total)))
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                        artifact.inputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var written = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                written += count
                                mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Writing(artifactId, fileName, written, total)))
                            }
                        }
                    } ?: error("无法打开目标文件。")
                }
                artifact.parentFile?.takeIf { it.name == artifactId }?.deleteRecursively() ?: artifact.delete()
                mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Idle), feedback = FeedbackMessage("已导出 Mod ZIP。"))
            } catch (error: Throwable) {
                artifact.parentFile?.takeIf { it.name == artifactId }?.deleteRecursively() ?: artifact.delete()
                mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Idle), feedback = FeedbackMessage("保存 Mod ZIP 失败：${error.message ?: "目标文件可能不完整"}", true))
            }
        }
    }

    fun finishModExportShare(artifactId: String) {
        val operation = mutableState.value.modExport.operation
        if (operation is ModExportOperation.Sharing && operation.artifactId == artifactId) {
            mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Idle))
        }
    }

    fun failModExportShare(artifactId: String, reason: String) {
        modExportArtifactFile(artifactId).delete()
        File(modExportRoot, artifactId).deleteRecursively()
        val operation = mutableState.value.modExport.operation
        if (operation is ModExportOperation.Sharing && operation.artifactId == artifactId) {
            mutableState.value = mutableState.value.copy(modExport = mutableState.value.modExport.copy(operation = ModExportOperation.Idle), feedback = FeedbackMessage(reason, true))
        }
    }

    private fun safeZipName(value: String): String {
        val cleaned = value.trim().filter { !it.isISOControl() && it != '/' && it != '\\' && it != '\u0000' }.trimEnd('.', ' ')
        val base = cleaned.removeSuffix(".zip").removeSuffix(".ZIP").ifBlank { "sultans-game-mods" }
        return "$base.zip"
    }
    fun openMerge() {
        val catalog = mergeCatalog
        val runtimeVersion = (mutableState.value.gameProbeResult as? GameProbeResult.Found)
            ?.snapshot
            ?.versionCode
        val catalogSelection = catalog?.let {
            when (runtimeVersion) {
                it.versionCode -> com.sultansgame.modmanager.merge.CatalogSelection(
                    it,
                    exactVersion = true,
                )
                null -> com.sultansgame.modmanager.merge.CatalogSelection(
                    it,
                    exactVersion = false,
                    warning = "无法确认当前游戏版本，将使用可用 ID Catalog 继续尝试。",
                )
                else -> com.sultansgame.modmanager.merge.CatalogSelection(
                    it,
                    exactVersion = false,
                    warning = "当前游戏版本与 ID Catalog 不匹配，将使用可用 Catalog 继续尝试。",
                )
            }
        }
        mutableState.value = mutableState.value.copy(
            merge = mutableState.value.merge.copy(
                isOpen = true,
                selectedCacheKeys = emptyList(),
                catalogSelection = catalogSelection,
                catalogError = catalog?.let { null } ?: mergeCatalogLoad.error,
                conflicts = emptyList(),
                warnings = emptyList(),
                preflight = MergePreflightState.Idle,
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
        mergePreflightJob?.cancel()
        val merge = mutableState.value.merge
        val selection = merge.selectedCacheKeys
        if (selection.size < 2 || merge.catalogSelection == null) {
            mutableState.value = mutableState.value.copy(
                merge = merge.copy(conflicts = emptyList(), preflight = MergePreflightState.Idle),
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            merge = merge.copy(preflight = MergePreflightState.Running(selection), conflicts = emptyList()),
        )
        mergePreflightJob = viewModelScope.launch {
            try {
                val preflight = withContext(Dispatchers.IO) {
                    com.sultansgame.modmanager.merge.MergeWorkspace(mergeRoot).use { workspace ->
                        val inputs = workspace.copyInputs(selection.map { key ->
                            File(File(getApplication<Application>().filesDir, "mod-cache"), key)
                        })
                        val catalog = File(workspace.directory, "catalog.json")
                        com.sultansgame.modmanager.merge.BaseIdCatalogJsonCodec().write(merge.catalogSelection.catalog, catalog)
                        val result = mergeBridge.remap(inputs, catalog, workspace.pythonOutputDirectory())
                        com.sultansgame.modmanager.merge.MergePreflight(
                            conflicts = result.conflicts.map { conflict ->
                                com.sultansgame.modmanager.merge.MergeIdConflict(
                                    conflict.entityType, conflict.id, conflict.modIndexes,
                                )
                            },
                            warnings = result.warnings.map { warning ->
                                com.sultansgame.modmanager.merge.MergeWarning(
                                    warning.code,
                                    warning.message,
                                    warning.entityType,
                                    warning.count,
                                )
                            } + listOfNotNull(merge.catalogSelection.warning?.let { warning ->
                                com.sultansgame.modmanager.merge.MergeWarning(
                                    code = "catalog_mismatch",
                                    message = warning,
                                )
                            }),
                            remappedEntries = result.remappedEntries,
                            catalogWarning = merge.catalogSelection.warning,
                            bestEffort = result.bestEffort || !merge.catalogSelection.exactVersion,
                        )
                    }
                }
                if (mutableState.value.merge.selectedCacheKeys == selection) {
                    mutableState.value = mutableState.value.copy(
                        merge = mutableState.value.merge.copy(
                            conflicts = preflight.conflicts,
                            warnings = preflight.warnings,
                            preflight = MergePreflightState.Ready(selection, preflight),
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (mutableState.value.merge.selectedCacheKeys == selection) {
                    val reason = error.message ?: "无法完成合并预检"
                    mutableState.value = mutableState.value.copy(
                        merge = mutableState.value.merge.copy(
                            preflight = MergePreflightState.Failed(selection, reason),
                        ),
                        feedback = FeedbackMessage("合并预检失败：$reason", isError = true),
                    )
                }
            }
        }
    }

    fun setMergeDisplayName(value: String) {
        mutableState.value = mutableState.value.copy(merge = mutableState.value.merge.copy(resultDisplayName = value))
    }

    fun startMerge() {
        val merge = mutableState.value.merge
        val selection = merge.catalogSelection ?: return
        val ready = merge.preflight as? MergePreflightState.Ready ?: return
        if (ready.selection != merge.selectedCacheKeys || merge.selectedCacheKeys.size < 2 || merge.isRunning) return
        mutableState.value = mutableState.value.copy(merge = merge.copy(isRunning = true, progress = "正在复制 Mod 并执行上游 ID 重映射…"))
        viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) {
                    com.sultansgame.modmanager.merge.MergeWorkspace(mergeRoot).use { workspace ->
                        val sourceRoots = merge.selectedCacheKeys.map { key ->
                            File(File(getApplication<Application>().filesDir, "mod-cache"), key)
                        }
                        val inputs = workspace.copyInputs(sourceRoots)
                        val catalog = File(workspace.directory, "catalog.json")
                        com.sultansgame.modmanager.merge.BaseIdCatalogJsonCodec().write(selection.catalog, catalog)
                        val remapped = mergeBridge.remap(inputs, catalog, workspace.pythonOutputDirectory())
                        val mergedOutput = remapped.mergedOutput
                        require(
                            mergedOutput.isDirectory &&
                                !java.nio.file.Files.isSymbolicLink(mergedOutput.toPath()),
                        ) {
                            "Python 合并输出目录不可读：${mergedOutput.absolutePath}"
                        }
                        privateModCache.importDirectory(
                            mergedOutput,
                            com.sultansgame.modmanager.model.CacheSource.Generated,
                            merge.resultDisplayName,
                        )
                    }
                }
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    merge = mutableState.value.merge.copy(isRunning = false, progress = null),
                    feedback = FeedbackMessage("合并失败：${error.message ?: "未知错误"}", isError = true),
                )
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

    fun renameCachedMod(cacheKey: String, rawName: String) {
        if (mutableState.value.cachedModDeletionInProgress || mutableState.value.gameModSyncInProgress) return
        val target = mutableState.value.cachedMods.firstOrNull { it.cacheKey == cacheKey } ?: return
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) {
                privateModCache.renameDisplayName(target.cacheKey, rawName)
            }) {
                is CachedModRenameResult.Renamed -> {
                    val updated = mutableState.value.cachedMods.map { cached ->
                        if (cached.cacheKey == target.cacheKey) cached.copy(displayName = result.displayName) else cached
                    }
                    mutableState.value = mutableState.value.copy(
                        cachedMods = updated,
                        feedback = FeedbackMessage("已更新 ${result.displayName} 的 Manager 显示名称。"),
                    )
                    refreshGameModSyncItems()
                }
                CachedModRenameResult.NotFound -> mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("重命名失败：${target.displayName} 已不存在。", isError = true),
                )
                is CachedModRenameResult.Rejected -> mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("重命名失败：${result.reason}", isError = true),
                )
                is CachedModRenameResult.Failed -> mutableState.value = mutableState.value.copy(
                    feedback = FeedbackMessage("重命名失败：${result.reason}", isError = true),
                )
            }
        }
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

    fun setWorkshopEnabled(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(showWorkshop = enabled)
        viewModelScope.launch { workshopVisibilitySettings.setWorkshopEnabled(enabled) }
    }

    fun openWorkshopNative() {
        if (isAllowedWorkshopNativeUrl(WORKSHOP_NATIVE_URL)) {
            uiEventChannel.trySend(ManagerUiEvent.OpenExternalUrl(WORKSHOP_NATIVE_URL))
        }
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

    // ==================== 存档编辑（WebView 承载上游 HTML 编辑器）====================

    private fun updateSaveEditor(transform: (SaveEditorUiState) -> SaveEditorUiState) {
        mutableState.value = mutableState.value.copy(saveEditor = transform(mutableState.value.saveEditor))
    }

    /**
     * Reports a failure both in this state and in the page's own status bar: the
     * editing stage is entirely WebView, so a Compose-only message would never
     * be seen.
     */
    private suspend fun saveEditorFailed(fallback: String, error: Throwable? = null) {
        val message = error?.message?.takeIf { it.isNotBlank() } ?: fallback
        updateSaveEditor { it.copy(isBusy = false, progress = null, error = message) }
        saveEditorWeb.showStatus("❌ $message")
    }

    private suspend fun saveEditorNoticed(message: String, transform: (SaveEditorUiState) -> SaveEditorUiState) {
        updateSaveEditor { transform(it).copy(isBusy = false, progress = null, notice = message) }
        saveEditorWeb.showStatus(message)
    }

    /**
     * Marks a long native operation as running and echoes it into the page's
     * status bar, which is the only progress indicator visible while the editor
     * covers the screen.
     */
    private suspend fun saveEditorBusy(progress: String) {
        updateSaveEditor { it.copy(isBusy = true, progress = progress, error = null, notice = null) }
        saveEditorWeb.showStatus("⏳ $progress")
    }

    /**
     * Events raised by the page itself. Arrives on the main thread; the page's
     * own save button is treated as a request to run the native save pipeline.
     */
    private fun onSaveEditorWebEvent(event: SaveEditorWebEvent) {
        when (event) {
            is SaveEditorWebEvent.ExportRequested -> saveSave()
            SaveEditorWebEvent.ToolsRequested -> updateSaveEditor { it.copy(toolsOpen = true) }
            // Both discard unsaved edits, so they are parked for the UI to
            // confirm rather than run from here.
            SaveEditorWebEvent.ReloadRequested -> updateSaveEditor {
                it.copy(pendingWebAction = SaveEditorWebAction.Reload)
            }
            SaveEditorWebEvent.LeaveRequested -> updateSaveEditor {
                it.copy(pendingWebAction = SaveEditorWebAction.Leave)
            }
            SaveEditorWebEvent.SaveInjected -> captureSaveBaseline()
            is SaveEditorWebEvent.LoadFailed -> updateSaveEditor {
                // The page's buttons stay disabled when a load fails, so surface
                // the reason on the native panel where 重新读取 / 返回列表 live.
                it.copy(
                    isBusy = false,
                    progress = null,
                    editorReady = false,
                    toolsOpen = true,
                    error = "编辑器无法载入该存档：${event.message}",
                )
            }
            SaveEditorWebEvent.RendererGone -> updateSaveEditor {
                it.copy(
                    isBusy = false,
                    progress = null,
                    editorReady = false,
                    savedBaseline = null,
                    // The page is gone, so its status bar cannot carry this; the
                    // native panel is the only surface left to report it on.
                    toolsOpen = true,
                    editorGeneration = it.editorGeneration + 1,
                    error = "编辑器页面已崩溃，未保存的修改已丢失。磁盘上的存档没有被改动，可重新读取存档继续。",
                )
            }
        }
    }

    /**
     * Records the page's serialization of the freshly loaded save. The page
     * re-serializes, so this — not the disk text — is what unsaved edits are
     * measured against.
     *
     * Deliberately not tracked by [saveEditorJob]: it is triggered by the page
     * while `selectSaveFile` is still finishing, and stealing that handle would
     * make the next user action look idle.
     */
    private fun captureSaveBaseline() {
        viewModelScope.launch {
            val baseline = saveEditorWeb.pullCurrentJson()
            updateSaveEditor { it.copy(editorReady = baseline != null, savedBaseline = baseline) }
        }
    }

    fun openSaveEditor() {
        val current = mutableState.value.saveEditor
        updateSaveEditor { it.copy(isOpen = true) }
        if (current.users.isEmpty() && current.selectedFile == null && !current.isBusy) {
            loadSaveUsers()
        }
    }

    /**
     * Leaving the tab only hides the editor. The WebView, the loaded save, and
     * any unsaved edits stay alive so returning to the tab resumes editing.
     */
    fun closeSaveEditor() {
        updateSaveEditor { it.copy(isOpen = false) }
    }

    fun closeSaveEditorTools() {
        updateSaveEditor { it.copy(toolsOpen = false) }
    }

    /** Clears a page-raised action once the UI has run or declined it. */
    fun consumeSaveEditorWebAction() {
        updateSaveEditor { it.copy(pendingWebAction = null) }
    }

    /**
     * Hands the retained editor view to the composition. [context] must be the
     * activity context: the view borrows it for as long as it stays attached so
     * the page's own popups have a window to live in.
     */
    fun attachSaveEditorView(context: android.content.Context): android.view.View =
        saveEditorWeb.attach(context)

    fun detachSaveEditorView() {
        saveEditorWeb.detach()
    }

    fun loadSaveUsers() {
        if (saveEditorJob?.isActive == true || mutableState.value.saveEditor.isBusy) return
        saveEditorJob = viewModelScope.launch {
            updateSaveEditor { it.copy(isBusy = true, error = null) }
            try {
                val status = withContext(Dispatchers.IO) { loaderBridge.listSaveUsers() }
                when (status.availability) {
                    GameSaveAvailability.Available -> updateSaveEditor {
                        it.copy(isBusy = false, users = status.users, stage = SaveEditorStage.SelectUser)
                    }
                    // Save access shipped after the mod-sync provider, so a game
                    // patched by an older manager answers these calls with invalid.
                    GameSaveAvailability.ProviderTooOld -> updateSaveEditor {
                        it.copy(
                            isBusy = false,
                            error = "当前游戏未启用存档编辑，请重新修补并安装匹配的游戏版本。",
                        )
                    }
                    else -> updateSaveEditor {
                        it.copy(isBusy = false, error = status.reason ?: "无法读取游戏存档。")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                saveEditorFailed("无法读取游戏存档。", error)
            }
        }
    }

    fun selectSaveUser(uid: String) {
        if (mutableState.value.saveEditor.isBusy) return
        saveEditorJob = viewModelScope.launch {
            updateSaveEditor {
                it.copy(isBusy = true, selectedUser = uid, saveFiles = emptyList(), error = null)
            }
            try {
                val status = withContext(Dispatchers.IO) { loaderBridge.listSaveFiles(uid) }
                val slots = SaveArchiveIndex.slots(readArchiveIndex(uid))
                updateSaveEditor {
                    it.copy(
                        isBusy = false,
                        // A game patched before the provider's dedup fix reports
                        // user_archive.json twice.
                        saveFiles = status.files.distinct(),
                        archiveSlots = slots,
                        stage = SaveEditorStage.SelectFile,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                saveEditorFailed("无法读取存档文件列表。", error)
            }
        }
    }

    fun selectSaveFile(fileName: String) {
        val current = mutableState.value.saveEditor
        val uid = current.selectedUser ?: return
        if (current.isBusy) return
        saveEditorJob = viewModelScope.launch {
            updateSaveEditor {
                it.copy(
                    isBusy = true,
                    selectedFile = fileName,
                    editorReady = false,
                    savedBaseline = null,
                    error = null,
                    notice = null,
                )
            }
            try {
                val status = withContext(Dispatchers.IO) { loaderBridge.readSave(uid, fileName) }
                if (!status.isReady) {
                    saveEditorFailed(status.reason ?: "无法读取存档文件。")
                    return@launch
                }
                val rawJson = status.content ?: throw IllegalStateException("存档内容为空")
                val backups = listSaveBackups(uid, fileName)
                // The page parses the text and reports back through
                // SaveInjected/LoadFailed, which is where editorReady is settled.
                saveEditorWeb.load(rawJson, fileName)
                updateSaveEditor {
                    it.copy(
                        isBusy = false,
                        rawJson = rawJson,
                        backups = backups,
                        stage = SaveEditorStage.Edit,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                saveEditorFailed("无法读取存档文件。", error)
            }
        }
    }

    /**
     * Re-reads the open save from the game, discarding unsaved edits. The game
     * rewrites its save on exit, so a stale in-memory copy must be refreshable.
     */
    fun reloadSaveFile() {
        val fileName = mutableState.value.saveEditor.selectedFile ?: return
        if (mutableState.value.saveEditor.isBusy) return
        viewModelScope.launch {
            // Wipe the page first so a failed re-read cannot leave stale edits on
            // screen looking like they are still backed by the file.
            saveEditorWeb.reset()
            updateSaveEditor {
                it.copy(
                    editorReady = false,
                    savedBaseline = null,
                    toolsOpen = false,
                    pendingWebAction = null,
                )
            }
            selectSaveFile(fileName)
        }
    }

    /** Returns to the file list, dropping the page's state along with it. */
    fun leaveSaveFile() {
        if (mutableState.value.saveEditor.isBusy) return
        saveEditorJob = viewModelScope.launch {
            saveEditorWeb.reset()
            updateSaveEditor {
                it.copy(
                    stage = SaveEditorStage.SelectFile,
                    selectedFile = null,
                    rawJson = null,
                    savedBaseline = null,
                    editorReady = false,
                    toolsOpen = false,
                    pendingWebAction = null,
                    backups = emptyList(),
                    error = null,
                    notice = null,
                )
            }
        }
    }

    /**
     * Whether the page holds edits that are not on disk. Computed on demand
     * because the edits live in the page, not in this state.
     */
    suspend fun saveEditorHasUnsavedEdits(): Boolean {
        val baseline = mutableState.value.saveEditor.savedBaseline ?: return false
        val current = saveEditorWeb.pullCurrentJson() ?: return false
        return current != baseline
    }

    /** Raw `user_archive.json` text, or null when absent/unreadable. */
    private suspend fun readArchiveIndex(uid: String): String? {
        val status = runCatching {
            withContext(Dispatchers.IO) { loaderBridge.readSave(uid, "user_archive.json") }
        }.getOrNull() ?: return null
        return status.content?.takeIf { status.isReady }
    }

    /** Manager-side backups of one save file, newest first. */
    private suspend fun listSaveBackups(uid: String, fileName: String): List<SaveBackupEntry> =
        runCatching { withContext(Dispatchers.IO) { saveBackupStore.list(uid, fileName) } }
            .getOrElse { emptyList() }

    /**
     * Snapshots the content the manager is about to overwrite. Callers must fail
     * closed when this throws: the game side keeps only a single `.sgmm-bak`
     * generation, so writing without a manager-side backup leaves no way back.
     */
    private suspend fun backupBeforeWrite(uid: String, fileName: String, content: String) {
        withContext(Dispatchers.IO) { saveBackupStore.create(uid, fileName, content) }
    }

    /** Backs up an overwrite target that already exists; absent files need none. */
    private suspend fun backupExistingTarget(uid: String, fileName: String) {
        val status = runCatching {
            withContext(Dispatchers.IO) { loaderBridge.readSave(uid, fileName) }
        }.getOrNull() ?: return
        val content = status.content?.takeIf { status.isReady } ?: return
        backupBeforeWrite(uid, fileName, content)
    }

    /**
     * Overwrites the open save with the page's current content.
     *
     * Order matters: verify the disk copy still matches what we read, back it up
     * (aborting the whole save if that fails), and only then write.
     */
    fun saveSave() {
        val current = mutableState.value.saveEditor
        val uid = current.selectedUser ?: return
        val fileName = current.selectedFile ?: return
        val rawJson = current.rawJson ?: return
        if (current.isBusy || !current.editorReady) return
        saveEditorJob = viewModelScope.launch {
            saveEditorBusy("正在保存存档…")
            try {
                val edited = saveEditorWeb.pullCurrentJson()
                if (edited == null) {
                    saveEditorFailed("编辑器内的存档数据无效，未做任何写入。请重新读取存档。")
                    return@launch
                }
                if (edited == current.savedBaseline) {
                    saveEditorNoticed("ℹ️ 没有需要保存的修改。") { it }
                    return@launch
                }
                // 覆盖原存档前先确认磁盘内容仍是我们读到的那份，避免盖掉游戏后来写入的进度。
                // 这替代上游桌面版的 QFileSystemWatcher（Android 侧无法直接监视游戏私有目录）。
                val disk = withContext(Dispatchers.IO) { loaderBridge.readSave(uid, fileName) }
                val diskContent = disk.content
                if (disk.isReady && diskContent != null && diskContent != rawJson) {
                    saveEditorFailed(
                        "存档文件已被外部改动（通常是游戏又写入了一次）。" +
                            "请先“重新读取存档”，确认后再保存，以免覆盖新的游戏进度。",
                    )
                    return@launch
                }
                // 覆盖前先在应用私有目录留一份可恢复备份。游戏侧的 .sgmm-bak 只保留上一代，
                // 备份失败即中止：宁可不改，也不要在没有退路的情况下覆盖存档。
                try {
                    backupBeforeWrite(uid, fileName, diskContent ?: rawJson)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    saveEditorFailed("无法创建存档备份，已取消本次保存：${error.message ?: "未知错误"}")
                    return@launch
                }
                val writeStatus = withContext(Dispatchers.IO) {
                    loaderBridge.writeSave(uid, fileName, edited)
                }
                if (!writeStatus.isReady) {
                    saveEditorFailed(writeStatus.reason ?: "保存存档失败。")
                    return@launch
                }
                val savedBackups = listSaveBackups(uid, fileName)
                saveEditorNoticed("✅ 存档已保存；覆盖前的版本已备份，可在“槽位 / 备份”里恢复。") {
                    it.copy(rawJson = edited, savedBaseline = edited, backups = savedBackups)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                saveEditorFailed("保存存档失败。", error)
            }
        }
    }

    /**
     * Saves the page's content into one of the game's ten load-menu slots. That
     * means two writes: the save itself and a summary row in `user_archive.json`,
     * whose numbers the page computes from its own bundled card catalog.
     */
    fun saveSaveArchive(slot: Int, name: String) {
        val current = mutableState.value.saveEditor
        val uid = current.selectedUser ?: return
        if (slot !in 0 until SaveArchiveIndex.SLOT_COUNT || current.isBusy || !current.editorReady) return
        val slotName = name.trim().takeIf { it.isNotEmpty() } ?: "未命名存档"
        val fileName = SaveArchiveIndex.fileNameFor(slot)
        saveEditorJob = viewModelScope.launch {
            saveEditorBusy("正在保存到存档槽位…")
            try {
                val edited = saveEditorWeb.pullCurrentJson()
                if (edited == null) {
                    saveEditorFailed("编辑器内的存档数据无效，未做任何写入。请重新读取存档。")
                    return@launch
                }
                // Fail closed: without a summary the game's load menu would show a
                // slot it cannot describe, so no file is written at all.
                val summary = saveEditorWeb.pullArchiveSummary()
                if (summary == null) {
                    saveEditorFailed("无法生成槽位摘要，已取消本次保存。请重新读取存档后再试。")
                    return@launch
                }
                val archiveIndex = readArchiveIndex(uid)
                val (indexJson, slots) = SaveArchiveIndex.withSlot(archiveIndex, slot, slotName, summary)
                // 槽位文件与索引都可能已有内容，覆盖前各留一份可恢复备份。
                backupExistingTarget(uid, fileName)
                if (archiveIndex != null) backupBeforeWrite(uid, "user_archive.json", archiveIndex)
                val status = withContext(Dispatchers.IO) { loaderBridge.writeSave(uid, fileName, edited) }
                if (!status.isReady) {
                    saveEditorFailed(status.reason ?: "保存槽位失败。")
                    return@launch
                }
                val indexStatus = withContext(Dispatchers.IO) {
                    loaderBridge.writeSave(uid, "user_archive.json", indexJson)
                }
                val refreshedBackups = listSaveBackups(uid, current.selectedFile ?: fileName)
                val message = if (indexStatus.isReady) {
                    "✅ 已保存到第 ${slot + 1} 个存档槽位（$slotName）。"
                } else {
                    "⚠️ 槽位文件已写入，但存档列表索引更新失败：${indexStatus.reason ?: "未知错误"}"
                }
                saveEditorNoticed(message) {
                    it.copy(
                        saveFiles = (it.saveFiles + fileName).distinct().sorted(),
                        backups = refreshedBackups,
                        archiveSlots = if (indexStatus.isReady) slots else it.archiveSlots,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                saveEditorFailed("保存槽位失败。", error)
            }
        }
    }

    /**
     * Writes a manager-side backup back into the game's save directory. The
     * current content is snapshotted first, so a restore is itself undoable.
     */
    fun restoreSaveBackup(entry: SaveBackupEntry) {
        val current = mutableState.value.saveEditor
        val uid = current.selectedUser ?: return
        if (current.isBusy) return
        saveEditorJob = viewModelScope.launch {
            saveEditorBusy("正在恢复备份…")
            try {
                val content = withContext(Dispatchers.IO) { saveBackupStore.read(entry) }
                backupExistingTarget(uid, entry.fileName)
                val status = withContext(Dispatchers.IO) {
                    loaderBridge.writeSave(uid, entry.fileName, content)
                }
                if (!status.isReady) {
                    saveEditorFailed(status.reason ?: "恢复备份失败。")
                    return@launch
                }
                val reopened = entry.fileName == current.selectedFile
                if (reopened) {
                    // The restored text has to go back through the page, so drop
                    // the baseline until it reports the reload succeeded.
                    saveEditorWeb.reset()
                    saveEditorWeb.load(content, entry.fileName)
                }
                val backups = listSaveBackups(uid, current.selectedFile ?: entry.fileName)
                saveEditorNoticed(
                    "✅ 已把 ${entry.fileName} 恢复到 ${entry.createdAtText} 的备份；恢复前的内容也已另存为备份。",
                ) {
                    it.copy(
                        rawJson = if (reopened) content else it.rawJson,
                        editorReady = if (reopened) false else it.editorReady,
                        savedBaseline = if (reopened) null else it.savedBaseline,
                        backups = backups,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                saveEditorFailed("恢复备份失败。", error)
            }
        }
    }

    fun deleteSaveBackup(entry: SaveBackupEntry) {
        val current = mutableState.value.saveEditor
        val uid = current.selectedUser ?: return
        if (current.isBusy) return
        saveEditorJob = viewModelScope.launch {
            val removed = runCatching {
                withContext(Dispatchers.IO) { saveBackupStore.delete(entry) }
            }.getOrDefault(false)
            val backups = listSaveBackups(uid, current.selectedFile ?: entry.fileName)
            updateSaveEditor {
                it.copy(
                    backups = backups,
                    notice = if (removed) "已删除 ${entry.createdAtText} 的备份。" else "该备份文件已不存在。",
                )
            }
        }
    }

    fun reportExternalImportError(reason: String) {
        mutableState.value = mutableState.value.copy(feedback = FeedbackMessage(reason, isError = true))
    }

    fun resetManagerState() {
        if (mutableState.value.cachedModDeletionInProgress || mutableState.value.zipImportInProgress || mutableState.value.merge.isRunning) {
            mutableState.value = mutableState.value.copy(
                feedback = FeedbackMessage("当前操作正在进行，请稍后再试。", isError = true),
            )
            return
        }
        mutableState.value = mutableState.value.copy(feedback = FeedbackMessage("正在重置管理器状态…"))
        workshopBrowseJob?.cancel()
        steamGuardSubmissionJob?.cancel()
        gameModSyncJob?.cancel()
        mergePreflightJob?.cancel()
        updateCheckJob?.cancel()
        saveEditorJob?.cancel()
        saveEditorWeb.recycle()
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    taskStore.tasks.value.forEach { downloadScheduler.cancel(it.id) }
                    val importing = taskStore.reset()
                    importing.forEach { failures += "Workshop 任务 ${it.id} 正在导入" }
                    artifactImporter.clearStagingExcept(importing.mapTo(mutableSetOf(), DownloadTask::id))
                    externalZipInbox.clear()
                    privateModCache.resetPreservingMods().forEach { failures += "Mod 缓存 $it" }
                    deploymentPlan.reset()
                    transactions.sessionIds().forEach { sessionId ->
                        if (!packageInstaller.abandonSession(sessionId)) failures += "系统安装会话 $sessionId"
                    }
                    transactions.resetAll().forEach { failures += "修补事务 $it" }
                    mergeRoot.listFiles()?.forEach { it.deleteRecursively() }
                    getApplication<Application>().cacheDir.listFiles()
                        ?.filter { it.name.startsWith(".zip-import-") }
                        ?.forEach { it.delete() }
                    legalNotice.reset()
                    updateCheckSettings.reset()
                    steamAuthProvider.logout()
                }
            } catch (error: Exception) {
                failures += error.message ?: error::class.java.simpleName
            }
            val cachedMods = withContext(Dispatchers.IO) { privateModCache.listCached() }
            val current = mutableState.value
            mutableState.value = current.copy(
                cachedMods = cachedMods,
                gameModSyncItems = deploymentPlan.entries(cachedMods),
                pendingGameModSyncOperations = deploymentPlan.pendingOperations(),
                downloadTasks = taskStore.tasks.value,
                steamAuthState = com.sultansgame.modmanager.model.SteamAuthState.SignedOut,
                noticeAccepted = false,
                autoUpdateCheckEnabled = true,
                availableUpdate = null,
                pendingExternalZip = null,
                pendingZipPassword = false,
                zipImportInProgress = false,
                workshop = WorkshopUiState.Idle,
                workshopBrowse = WorkshopBrowseUiState(),
                merge = MergeUiState(),
                patchCleanup = null,
                patchCleanupConfirmation = null,
                apksExport = ApksExportUiState.Idle,
                saveEditor = SaveEditorUiState(isOpen = current.saveEditor.isOpen),
                feedback = if (failures.isEmpty()) {
                    FeedbackMessage("已重置管理器状态，保留 ${cachedMods.size} 个 Mod 和设备签名密钥。")
                } else {
                    FeedbackMessage("管理器状态已部分重置：${failures.joinToString("、")}", isError = true)
                },
            )
            refreshGame()
            refreshGameModSync()
        }
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

    /**
     * The retained save-editor WebView is only ever destroyed here: it must
     * outlive tab switches and rotation, so this is the one point at which the
     * editor is genuinely finished with.
     */
    override fun onCleared() {
        saveEditorWeb.destroy()
        super.onCleared()
    }

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
        private const val LOG_TAG = "ManagerViewModel"
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
