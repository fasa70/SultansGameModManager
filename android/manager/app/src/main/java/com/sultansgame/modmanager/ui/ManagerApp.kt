package com.sultansgame.modmanager.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.CircularProgressIndicator
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.sultansgame.modmanager.R
import com.sultansgame.modmanager.ApksExportUiState
import com.sultansgame.modmanager.FeedbackMessage
import com.sultansgame.modmanager.ManagerUiState
import com.sultansgame.modmanager.MergePreflightState
import com.sultansgame.modmanager.PatchUiState
import com.sultansgame.modmanager.PreparedPatchRecovery
import com.sultansgame.modmanager.WORKSHOP_NATIVE_URL
import com.sultansgame.modmanager.WorkshopUiState
import com.sultansgame.modmanager.model.Compatibility
import com.sultansgame.modmanager.model.DeviceSigningKeyState
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.GameModSyncAvailability
import com.sultansgame.modmanager.model.GameModSyncItem
import com.sultansgame.modmanager.model.PatchConfirmation
import com.sultansgame.modmanager.model.SteamAuthState
import com.sultansgame.modmanager.model.WorkshopBrowseQuery
import com.sultansgame.modmanager.model.WorkshopDateRangeFilter
import com.sultansgame.modmanager.model.WorkshopBrowseTagGroupSelectionMode
import com.sultansgame.modmanager.model.WorkshopItem
import com.sultansgame.modmanager.platform.game.GameProbeResult
import com.sultansgame.modmanager.workshop.WorkshopHttpPolicy
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** All user actions retained from [com.sultansgame.modmanager.ManagerViewModel]. */
data class ManagerActions(
    val importMod: () -> Unit,
    val importLocalApk: () -> Unit,
    val importLocalApkSet: () -> Unit,
    val selectInstalledGame: () -> Unit,
    val preparePatch: () -> Unit,
    val refreshPendingPatch: () -> Unit,
    val requestOriginalUninstall: (String) -> Unit,
    val installPreparedArtifacts: (String) -> Unit,
    val exportPreparedApks: (String) -> Unit,
    val openUnknownSourcesSettings: () -> Unit,
    val restartPatch: () -> Unit,
    val resumePreparedPatch: (String) -> Unit,
    val updatePatchConfirmation: (PatchConfirmation) -> Unit,
    val requestPatchCleanup: () -> Unit,
    val confirmPatchCleanup: () -> Unit,
    val dismissPatchCleanup: () -> Unit,
    val browseWorkshop: (WorkshopBrowseQuery) -> Unit,
    val lookupWorkshop: (String) -> Unit,
    val beginSteamLogin: (String, String, Boolean) -> Unit,
    val submitSteamGuard: (String) -> Unit,
    val checkPendingSteamLogin: () -> Unit,
    val logoutSteam: () -> Unit,
    val queueWorkshopDownload: (WorkshopItem) -> Unit,
    val retryWorkshopDownload: (String) -> Unit,
    val pauseWorkshopDownload: (String) -> Unit,
    val resumeWorkshopDownload: (String) -> Unit,
    val cancelWorkshopDownload: (String) -> Unit,
    val confirmWorkshopImport: (String) -> Unit,
    val discardWorkshopArtifact: (String) -> Unit,
    val removeWorkshopDownload: (String) -> Unit,
    val refreshGameMods: () -> Unit,
    val launchGameForModSync: () -> Unit,
    val setModSyncedToGame: (String, Boolean) -> Unit,
    val deleteCachedMod: (String) -> Unit,
    val renameCachedMod: (String, String) -> Unit,
    val clearModCache: () -> Unit,
    val resetManagerState: () -> Unit = {},
    val openMerge: () -> Unit = {},
    val closeMerge: () -> Unit = {},
    val openModExport: () -> Unit = {},
    val closeModExport: () -> Unit = {},
    val toggleModExport: (String) -> Unit = {},
    val setModExportSelection: (List<String>) -> Unit = {},
    val selectAllModExport: () -> Unit = {},
    val requestModExport: (com.sultansgame.modmanager.ModExportAction) -> Unit = {},
    val submitModExport: (String, CharArray) -> Unit = { _, password -> password.fill('\u0000') },
    val cancelModExportSettings: () -> Unit = {},
    val toggleMergeMod: (String) -> Unit = {},
    val moveMergeMod: (Int, Int) -> Unit = { _, _ -> },
    val startMerge: () -> Unit = {},
    val setMergeDisplayName: (String) -> Unit = {},
    val keepOriginalSync: () -> Unit = {},
    val stopOriginalSync: () -> Unit = {},
    val openSaveEditor: () -> Unit = {},
    val closeSaveEditor: () -> Unit = {},
    val loadSaveUsers: () -> Unit = {},
    val selectSaveUser: (String) -> Unit = {},
    val selectSaveFile: (String) -> Unit = {},
    val leaveSaveFile: () -> Unit = {},
    val reloadSaveFile: () -> Unit = {},
    val saveSave: () -> Unit = {},
    val saveSaveArchive: (Int, String) -> Unit = { _, _ -> },
    val restoreSaveBackup: (com.sultansgame.modmanager.platform.saveeditor.SaveBackupEntry) -> Unit = {},
    val deleteSaveBackup: (com.sultansgame.modmanager.platform.saveeditor.SaveBackupEntry) -> Unit = {},
    val closeSaveEditorTools: () -> Unit = {},
    /** Clears a page-raised 重新读取 / 返回 request after the UI handled it. */
    val consumeSaveEditorWebAction: () -> Unit = {},
    /** Hands over the retained editor WebView; see ManagerViewModel.attachSaveEditorView. */
    val attachSaveEditorView: (android.content.Context) -> android.view.View? = { null },
    val detachSaveEditorView: () -> Unit = {},
    val saveEditorHasUnsavedEdits: suspend () -> Boolean = { false },
    val acceptNotice: () -> Unit,
    val setAutoUpdateCheckEnabled: (Boolean) -> Unit,
    val setWorkshopEnabled: (Boolean) -> Unit,
    val openWorkshopNative: () -> Unit,
    val dismissAvailableUpdate: () -> Unit,
    val openAvailableUpdate: () -> Unit,
    val clearFeedback: () -> Unit,
    val confirmExternalZipImport: () -> Unit,
    val submitZipPassword: (CharArray) -> Unit,
    val cancelExternalZipImport: () -> Unit,
)

private sealed interface DialogKind {
    data object Notice : DialogKind
    data object Privacy : DialogKind
    data object License : DialogKind
    data object ClearCache : DialogKind
    data object ResetManagerState : DialogKind
    data class DeleteCachedMod(val cacheKey: String) : DialogKind
    data class RenameCachedMod(val cacheKey: String) : DialogKind
    data object PatchCleanup : DialogKind
    data class DeviceInstallRisk(val warning: DeviceInstallWarning) : DialogKind
    data object UpdateAvailable : DialogKind
    data class WorkshopTaskRemoval(val taskId: String) : DialogKind
    data object ExternalZipImport : DialogKind
    data object ZipPasswordImport : DialogKind
    data object MergeSyncConfirmation : DialogKind
    data class ModExportSettings(val action: com.sultansgame.modmanager.ModExportAction) : DialogKind
}

@Composable
fun ManagerApp(state: ManagerUiState, actions: ManagerActions) {
    var selectedRoute by rememberSaveable { mutableStateOf(Destination.Start.name) }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    var promptedDeviceInstallTransaction by remember { mutableStateOf<String?>(null) }
    val showWorkshop = state.showWorkshop == true
    val destinations = remember(showWorkshop) { visibleDestinations(showWorkshop) }
    val destination = effectiveDestination(destinationFromRoute(selectedRoute), showWorkshop)
    LaunchedEffect(destination) {
        if (selectedRoute != destination.name) selectedRoute = destination.name
        if (destination == Destination.SaveEditor) actions.openSaveEditor()
        else if (state.saveEditor.isOpen) actions.closeSaveEditor()
    }

    val readyToInstall = state.patch as? PatchUiState.ReadyToInstall
    val deviceInstallWarning = remember {
        deviceInstallWarningFor(Build.MANUFACTURER, Build.BRAND)
    }

    LaunchedEffect(state.merge.awaitingSyncDecision) {
        if (state.merge.awaitingSyncDecision) dialog = DialogKind.MergeSyncConfirmation
    }

    LaunchedEffect(readyToInstall?.transactionId, deviceInstallWarning) {
        if (readyToInstall == null) {
            promptedDeviceInstallTransaction = null
        } else if (deviceInstallWarning != null && promptedDeviceInstallTransaction != readyToInstall.transactionId) {
            promptedDeviceInstallTransaction = readyToInstall.transactionId
            dialog = DialogKind.DeviceInstallRisk(deviceInstallWarning)
        }
    }

    LaunchedEffect(state.patchCleanupConfirmation != null) {
        if (state.patchCleanupConfirmation != null) dialog = DialogKind.PatchCleanup
    }
    LaunchedEffect(state.availableUpdate, state.noticeAccepted) {
        if (state.availableUpdate != null && state.noticeAccepted == true) dialog = DialogKind.UpdateAvailable
    }

    LaunchedEffect(state.pendingExternalZip) {
        if (state.pendingExternalZip != null) dialog = DialogKind.ExternalZipImport
    }

    LaunchedEffect(state.pendingZipPassword) {
        if (state.pendingZipPassword) dialog = DialogKind.ZipPasswordImport
    }

    LaunchedEffect(state.modExport.settingsAction) {
        dialog = state.modExport.settingsAction?.let(DialogKind::ModExportSettings)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SideRail(destinations, destination) { selectedRoute = it.name }
                MainContent(
                    modifier = Modifier.weight(1f),
                    destination = destination,
                    state = state,
                    actions = actions,
                    wide = true,
                    onSelectDestination = { selectedRoute = it.name },
                    onShowDialog = { dialog = it },
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                CompactHeader(destination)
                MainContent(
                    modifier = Modifier.weight(1f),
                    destination = destination,
                    state = state,
                    actions = actions,
                    wide = false,
                    onSelectDestination = { selectedRoute = it.name },
                    onShowDialog = { dialog = it },
                )
                BottomNavigation(destinations, destination) { selectedRoute = it.name }
            }
        }
        state.feedback?.let { FeedbackBanner(it, actions.clearFeedback, wide) }
    }

    when (state.noticeAccepted) {
        null -> PreparingNoticeDialog()
        false -> LegalNoticeDialog(actions.acceptNotice)
        true -> Unit
    }
    DialogHost(
        state,
        actions,
        dialog,
        onDismiss = {
            if (dialog == DialogKind.UpdateAvailable) actions.dismissAvailableUpdate()
            if (dialog == DialogKind.ExternalZipImport) actions.cancelExternalZipImport()
            if (dialog == DialogKind.ZipPasswordImport) actions.cancelExternalZipImport()
            if (dialog == DialogKind.MergeSyncConfirmation) actions.keepOriginalSync()
            dialog = null
        },
    )
}

@Composable
private fun SideRail(destinations: List<Destination>, selected: Destination, onSelect: (Destination) -> Unit) {
    Column(Modifier.fillMaxHeight().width(276.dp).background(MiuixTheme.colorScheme.surfaceContainer).padding(horizontal = 18.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { BrandMark(44.dp); Spacer(Modifier.width(12.dp)); Column { Text("苏丹的游戏", fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("MOD MANAGER", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) } }
        Spacer(Modifier.height(28.dp)); Text("开始使用", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary); Spacer(Modifier.height(8.dp))
        destinations.forEach { destination -> DestinationItem(destination, selected == destination) { onSelect(destination) }; Spacer(Modifier.height(4.dp)) }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun DestinationItem(item: Destination, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) MiuixTheme.colorScheme.primaryVariant else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(item.caption, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun CompactHeader(destination: Destination) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandMark(32.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("苏丹的游戏 Mod 管理器", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(destination.caption, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp) {
    Image(
        painter = painterResource(R.mipmap.ic_launcher_photo),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape),
    )
}

@Composable
private fun BottomNavigation(destinations: List<Destination>, selected: Destination, onSelect: (Destination) -> Unit) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        destinations.forEach { destination ->
            val isSelected = selected == destination
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (isSelected) MiuixTheme.colorScheme.primaryVariant else Color.Transparent).clickable { onSelect(destination) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(destination.title, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun MainContent(
    modifier: Modifier,
    destination: Destination,
    state: ManagerUiState,
    actions: ManagerActions,
    wide: Boolean,
    onSelectDestination: (Destination) -> Unit,
    onShowDialog: (DialogKind) -> Unit,
) {
    Column(modifier) {
        if (wide) ContentHeader(destination)
        if (state.merge.isOpen) MergeModsScreen(state, actions, wide)
        else if (state.modExport.isOpen) ModExportScreen(state, actions, wide)
        else when (destination) {
            Destination.Start -> StartScreen(state, actions, wide, onSelectDestination)
            Destination.Acquire -> AcquireNavigation(state, actions, wide)
            Destination.Library -> MyModsScreen(state, actions, wide, onShowDialog)
            Destination.SaveEditor -> SaveEditorScreen(state, actions, wide)
            Destination.Settings -> SettingsScreen(state, actions, wide, onShowDialog)
        }
    }
}

@Composable
private fun ContentHeader(destination: Destination) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(destination.title, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(destination.caption, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun StartScreen(state: ManagerUiState, actions: ManagerActions, wide: Boolean, onSelectDestination: (Destination) -> Unit) {
    val presentation = startPresentation(state)
    ScreenList(wide) {
        item {
            HeroPanel(
                eyebrow = "准备游戏",
                title = presentation.title,
                body = presentation.body,
                action = presentation.primaryLabel,
                actionEnabled = presentation.primaryEnabled,
                onAction = if (state.patch is PatchUiState.Completed) {
                    { onSelectDestination(Destination.Library) }
                } else {
                    presentation.primaryAction(actions)
                },
            )
        }
        item { StartOperationStatusPanel(patch = state.patch) }
        if (presentation.showConfirmations) {
            item {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("由于安装修补过的游戏，会导致原本的游戏数据丢失，所以请您确认", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        val review = state.patch as PatchUiState.Review
                        ConfirmationCheckbox("我已经通过游戏内云存档等方式备份好了存档", review.confirmation.acknowledgedReinstallRequirement) {
                            actions.updatePatchConfirmation(review.confirmation.withSinglePatchConfirmation(it))
                        }
                    }
                }
            }
        }
        state.preparedPatchRecovery?.takeIf { state.patch is PatchUiState.ChooseSource }?.let { recovery ->
            item { ResumePatchCard(recovery, actions) }
        }
        if (state.patch is PatchUiState.ChooseSource) {
            item { SectionLabel("其他安装方式", "可选") }
            item { ImportButton("从本地选择 APK", onClick = actions.importLocalApk) }
            item { ImportButton("从本地选择 APKS", onClick = actions.importLocalApkSet) }
        }
        when (val patch = state.patch) {
            is PatchUiState.AwaitingOriginalUninstall -> {
                item { SecondaryButton("我已卸载，重新检查", onClick = actions.refreshPendingPatch) }
                item { ApksExportAction(state.apksExport, patch.transactionId, actions.exportPreparedApks) }
            }
            is PatchUiState.ReadyToInstall -> item { ApksExportAction(state.apksExport, patch.transactionId, actions.exportPreparedApks) }
            is PatchUiState.AwaitingInstallPermission -> patch.transactionId?.let { transactionId ->
                item { ApksExportAction(state.apksExport, transactionId, actions.exportPreparedApks) }
            }
            else -> Unit
        }
        val cleanup = state.patchCleanup
        item { SectionLabel("存储与清理", cleanup?.let { formatBytes(it.sizeBytes) } ?: "0 B") }
        item {
            val cleanupInProgress = state.patchCleanupInProgress
            SecondaryButton(
                label = when {
                    cleanupInProgress -> "正在清理临时文件…"
                    cleanup != null -> "清理临时文件（${formatBytes(cleanup.sizeBytes)}）"
                    else -> "暂无可清理临时文件"
                },
                enabled = cleanup != null && !cleanupInProgress,
                onClick = actions.requestPatchCleanup,
            )
        }
        item { DiagnosticPanel("诊断信息", presentation.diagnostics) }
        item { GitHubStarLink(actions.openWorkshopNative) }
    }
}

@Composable
private fun GitHubStarLink(onClick: () -> Unit) {
    Text("如果喜欢，记得点这里给我的仓库点亮一颗 ⭐～", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp))
}

@Composable
private fun StartOperationStatusPanel(patch: PatchUiState) {
    val status = patch.toStartOperationStatus()
    AnimatedVisibility(
        visible = status != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        status?.let { LoadingPanel(body = it.body, title = it.title) }
    }
}

internal data class StartOperationStatus(
    val title: String,
    val body: String,
)

internal fun PatchConfirmation.withSinglePatchConfirmation(confirmed: Boolean): PatchConfirmation = copy(
    acknowledgedInstallRisk = confirmed,
    acknowledgedRecoveryLimit = confirmed,
    acknowledgedReinstallRequirement = confirmed,
)

internal fun PatchUiState.toStartOperationStatus(): StartOperationStatus? = when (this) {
    is PatchUiState.Importing -> StartOperationStatus(
        title = "正在导入游戏安装包",
        body = "请不要关闭应用。",
    )
    is PatchUiState.Preparing -> StartOperationStatus(
        title = "正在修补游戏安装包",
        body = "请不要关闭应用。",
    )
    is PatchUiState.SubmittingInstall -> StartOperationStatus(
        title = "正在请求系统安装",
        body = "请稍候，系统安装确认页面即将打开。",
    )
    is PatchUiState.AwaitingSystemInstall -> StartOperationStatus(
        title = "正在等待系统安装",
        body = "请在系统页面完成操作；完成后返回此处继续核验。",
    )
    else -> null
}

private data class StartPresentation(
    val title: String,
    val body: String,
    val primaryLabel: String,
    val primaryEnabled: Boolean = true,
    val showConfirmations: Boolean = false,
    val diagnostics: String,
    val primaryAction: (ManagerActions) -> () -> Unit,
)

private fun startPresentation(state: ManagerUiState): StartPresentation = when (val patch = state.patch) {
    PatchUiState.ChooseSource -> {
        val found = state.gameProbeResult is GameProbeResult.Found
        StartPresentation(
            title = if (found) "检测到游戏已安装" else "检测到游戏未安装",
            body = if (found) "我们可以直接导入已安装的游戏进行修补" else "需要先从本地导入游戏安装包后才能进行修补",
            primaryLabel = if (found) "导入游戏安装包" else "从本地导入游戏安装包",
            primaryEnabled = true,
            diagnostics = gameProbeDiagnostic(state.gameProbeResult),
            primaryAction = { actions -> if (found) actions.selectInstalledGame else actions.importLocalApk },
        )
    }
    is PatchUiState.Importing -> StartPresentation("正在导入游戏安装包", "请不要关闭应用", "正在导入…", false, diagnostics = patch.label, primaryAction = { {} })
    is PatchUiState.Review -> {
        val unsupported = patch.input.classification.compatibility.compatibility == Compatibility.Unsupported
        val confirmationReady = patch.confirmation.permits(patch.input.classification.mode)
        StartPresentation(
            title = if (unsupported) "此游戏版本暂不支持" else "导入完成",
            body = if (unsupported) "此版本尚未加入安全支持列表，应用不会继续修改或安装。请使用游戏1.0.5版本进行修补" else "已完成基本检查。确认以下事项后，我们将会开始修补游戏安装包",
            primaryLabel = if (unsupported) "选择其他安装包" else "开始修补",
            primaryEnabled = unsupported || confirmationReady,
            showConfirmations = !unsupported,
            diagnostics = "来源：${patch.input.sourceLabel}\n版本：${patch.input.versionLabel}\n安装组件：${patch.input.splitCount + 1}\n签名：${patch.input.signerSummary}\n${patch.input.classification.compatibility.reasons.joinToString("\n")}",
            primaryAction = { actions -> if (unsupported) actions.restartPatch else actions.preparePatch },
        )
    }
    is PatchUiState.Preparing -> StartPresentation("正在修补游戏", "请不要关闭应用。", "正在修补…", false, diagnostics = "修补中的安装文件：${patch.input.sourceLabel}", primaryAction = { {} })
    is PatchUiState.AwaitingOriginalUninstall -> StartPresentation("请先卸载原游戏", "修补已完成。请在系统页面卸载当前游戏，返回后再继续安装。", "打开系统卸载页面", diagnostics = patch.summary, primaryAction = { actions -> { actions.requestOriginalUninstall(patch.transactionId) } })
            is PatchUiState.ReadyToInstall -> StartPresentation(
                title = if (patch.installMode == com.sultansgame.modmanager.model.PatchInstallMode.SameDeviceOverwrite) "可以覆盖更新 Mod 支持版游戏" else "可以安装 Mod 支持版游戏",
                body = patch.summary,
                primaryLabel = if (patch.installMode == com.sultansgame.modmanager.model.PatchInstallMode.SameDeviceOverwrite) "覆盖更新游戏" else "调用系统安装器",
                diagnostics = patch.summary,
                primaryAction = { actions -> { actions.installPreparedArtifacts(patch.transactionId) } },
            )
    is PatchUiState.SubmittingInstall -> StartPresentation("正在打开系统安装", "请稍候，马上会转到系统安装确认。", "正在处理…", false, diagnostics = patch.transactionId, primaryAction = { {} })
    is PatchUiState.AwaitingInstallPermission -> StartPresentation("需要允许安装应用权限", "请在系统设置允许此应用安装游戏", "前往系统设置", diagnostics = "准备事务：${patch.transactionId ?: "尚未创建"}", primaryAction = { it.openUnknownSourcesSettings })
    is PatchUiState.AwaitingSystemInstall -> StartPresentation("请在系统页面完成安装", "安装完成后回到这里，应用会核验游戏是否已准备好。", "等待安装", false, diagnostics = patch.transactionId, primaryAction = { {} })
    is PatchUiState.Completed -> StartPresentation("游戏安装成功", "现在可以使用mod", "浏览创意工坊", diagnostics = patch.transactionId, primaryAction = { {} })
    is PatchUiState.Failed -> StartPresentation("准备未完成", "这一步没有完成，游戏和 Mod 未被更改。请重新开始；仍有问题时可查看诊断信息。", "重新开始", diagnostics = patch.reason, primaryAction = { it.restartPatch })
}

@Composable
private fun StartPresentation.primaryAction(actions: ManagerActions): () -> Unit = primaryAction(actions)

@Composable
private fun ResumePatchCard(recovery: PreparedPatchRecovery, actions: ManagerActions) {
    NoticeStrip("继续未完成的安装", "发现上次准备的安装文件")
    PrimaryButton("继续安装", onClick = { actions.resumePreparedPatch(recovery.transactionId) })
}

@Composable
private fun AcquireNavigation(state: ManagerUiState, actions: ManagerActions, wide: Boolean) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "browse") {
        composable("browse") {
            AcquireModsScreen(state, actions, wide, onOpenDetail = { id ->
                actions.lookupWorkshop(id)
                navController.navigate("detail/$id")
            })
        }
        composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id")
            val detail = state.workshop as? WorkshopUiState.Item
            LaunchedEffect(id) { if (!id.isNullOrBlank() && detail?.item?.publishedFileId.toString() != id) actions.lookupWorkshop(id) }
            when {
                id.isNullOrBlank() -> ScreenList(wide) { item { SecondaryButton("返回创意工坊", onClick = { navController.popBackStack() }) } }
                detail != null && detail.item.publishedFileId.toString() == id -> WorkshopDetailScreen(detail.item, wide, onBack = { actions.lookupWorkshop(""); navController.popBackStack() })
                state.workshop is WorkshopUiState.Error -> ScreenList(wide) { item { FriendlyErrorPanel("暂时无法读取此 Mod", "请返回创意工坊重试。", (state.workshop as WorkshopUiState.Error).reason) } }
                else -> ScreenList(wide) { item { LoadingPanel("正在读取 Mod 信息…") } }
            }
        }
    }
}

@Composable
private fun AcquireModsScreen(state: ManagerUiState, actions: ManagerActions, wide: Boolean, onOpenDetail: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf(state.workshopBrowse.query.searchText) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var filterDraft by remember { mutableStateOf(state.workshopBrowse.query) }
    LaunchedEffect(state.workshopBrowse.query) { filterDraft = state.workshopBrowse.query }
    LaunchedEffect(state.workshopBrowse.items.isEmpty(), state.workshopBrowse.error, state.workshopBrowse.hasLoadedOnce, state.workshopBrowse.isRefreshing) {
        if (state.workshopBrowse.items.isEmpty() && state.workshopBrowse.error == null && !state.workshopBrowse.hasLoadedOnce && !state.workshopBrowse.isRefreshing) {
            actions.browseWorkshop(WorkshopBrowseQuery())
        }
    }
    val submitSearch = { actions.browseWorkshop(state.workshopBrowse.query.copy(searchText = query, page = 1).normalized()) }
    ScreenList(wide) {
        item { NoticeStrip("创意工坊浏览模式", "因登录与下载功能不稳定，现已将相关功能隐藏，只开放浏览访问功能。") }
        item { WorkshopIntroPanel(actions) }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("搜索创意工坊", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                        LabeledTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = "关键词",
                            onSubmit = submitSearch,
                            modifier = Modifier.weight(1f),
                        )
                        InlinePrimaryButton(
                            label = "搜索",
                            enabled = !state.workshopBrowse.isRefreshing,
                            onClick = submitSearch,
                            modifier = Modifier.width(84.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallAction("热门", !state.workshopBrowse.isRefreshing) { actions.browseWorkshop(state.workshopBrowse.query.copy(searchText = query, sortKey = WorkshopBrowseQuery.SORT_TREND, page = 1)) }
                        SmallAction("最新", !state.workshopBrowse.isRefreshing) { actions.browseWorkshop(state.workshopBrowse.query.copy(searchText = query, sortKey = WorkshopBrowseQuery.SORT_MOST_RECENT, page = 1)) }
                        SmallAction("筛选", !state.workshopBrowse.isRefreshing) { showFilters = true }
                    }
                }
            }
        }
        state.workshopBrowse.error?.let { item { FriendlyErrorPanel("暂时无法获取创意工坊", "请检查网络后重试。", it) } }
        if (state.workshopBrowse.isRefreshing) {
            item {
                LoadingPanel(
                    if (state.workshopBrowse.items.isEmpty()) "正在浏览公开 Mod…" else "正在更新创意工坊结果…",
                )
            }
        }
        if (state.workshopBrowse.items.isNotEmpty()) {
            item { SectionLabel("推荐 Mod", "${state.workshopBrowse.totalCount} 项") }
            items(state.workshopBrowse.items, key = { it.publishedFileId.toString() }) { item -> WorkshopBrowseItemCard(item) { onOpenDetail(item.publishedFileId.toString()) } }
            if (state.workshopBrowse.hasMore) item { PrimaryButton(if (state.workshopBrowse.isLoadingMore) "正在加载…" else "加载更多", !state.workshopBrowse.isLoadingMore && !state.workshopBrowse.isRefreshing) { actions.browseWorkshop(state.workshopBrowse.query.copy(page = state.workshopBrowse.query.page + 1)) } }
        } else if (state.workshopBrowse.hasLoadedOnce && state.workshopBrowse.error == null) item { EmptyPanel("没有找到 Mod", "试试其他关键词或清除筛选条件。") }
    }
    if (showFilters) FilterDialog(state, query, filterDraft, { filterDraft = it }, { query = it }, { result -> actions.browseWorkshop(result); showFilters = false }, { showFilters = false })
}

@Composable
private fun FilterDialog(
    state: ManagerUiState,
    query: String,
    draft: WorkshopBrowseQuery,
    onDraftChange: (WorkshopBrowseQuery) -> Unit,
    onQueryChange: (String) -> Unit,
    onApply: (WorkshopBrowseQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("筛选 Mod", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("只保留常用条件。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (state.workshopBrowse.sectionOptions.isNotEmpty()) {
                    Text("分类", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    state.workshopBrowse.sectionOptions.forEach { option -> SmallAction(if (draft.sectionKey == option.key) "✓ ${option.label}" else option.label) { onDraftChange(draft.copy(sectionKey = option.key)) } }
                }
                if (state.workshopBrowse.sortOptions.isNotEmpty()) {
                    Text("排序", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    state.workshopBrowse.sortOptions.take(4).forEach { option -> SmallAction(if (draft.sortKey == option.key) "✓ ${option.label}" else option.label) { onDraftChange(draft.copy(sortKey = option.key)) } }
                }
                val selectedSort = state.workshopBrowse.sortOptions.firstOrNull { it.key == draft.sortKey }
                if (selectedSort?.supportsPeriod == true) {
                    Text("时间范围", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    state.workshopBrowse.periodOptions.forEach { option -> SmallAction(if (draft.periodDays == option.days) "✓ ${option.label}" else option.label) { onDraftChange(draft.copy(periodDays = option.days)) } }
                }
                state.workshopBrowse.tagGroups.take(2).forEach { group ->
                    Text(group.label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    group.tags.take(8).forEach { tag ->
                        val selected = tag.value in draft.requiredTags
                        val excluded = tag.value in draft.excludedTags
                        val tagLabel = when {
                            selected -> "✓ 包含 ${tag.label}"
                            excluded -> "− 排除 ${tag.label}"
                            else -> tag.label
                        }
                        SmallAction(tagLabel) {
                            val groupValues = group.tags.map { it.value }.toSet()
                            onDraftChange(
                                if (group.selectionMode == WorkshopBrowseTagGroupSelectionMode.SingleSelect) draft.copy(requiredTags = (draft.requiredTags - groupValues) + tag.value, excludedTags = draft.excludedTags - groupValues)
                                else when {
                                    tag.value !in draft.requiredTags && tag.value !in draft.excludedTags -> draft.copy(requiredTags = draft.requiredTags + tag.value, excludedTags = draft.excludedTags - tag.value)
                                    tag.value in draft.requiredTags -> draft.copy(requiredTags = draft.requiredTags - tag.value, excludedTags = draft.excludedTags + tag.value)
                                    else -> draft.copy(excludedTags = draft.excludedTags - tag.value)
                                },
                            )
                        }
                    }
                }
                if (state.workshopBrowse.supportsIncompatibleFilter) ConfirmationCheckbox("显示不兼容项", draft.showIncompatible) { onDraftChange(draft.copy(showIncompatible = it)) }
                DateRangeEditor("按创建时间筛选", draft.createdDateRange) { onDraftChange(draft.copy(createdDateRange = it)) }
                DateRangeEditor("按更新时间筛选", draft.updatedDateRange) { onDraftChange(draft.copy(updatedDateRange = it)) }
                PrimaryButton("应用筛选") { onApply(draft.copy(searchText = query, page = 1).normalized()) }
                SecondaryButton("重置筛选") { onQueryChange(query); onDraftChange(WorkshopBrowseQuery(searchText = query)) }
            }
        }
    }
}

@Composable
private fun DateRangeEditor(title: String, range: WorkshopDateRangeFilter, onChange: (WorkshopDateRangeFilter) -> Unit) {
    var start by remember(range.startEpochSeconds) { mutableStateOf(range.startEpochSeconds.takeIf { it > 0L }?.toString().orEmpty()) }
    var end by remember(range.endEpochSeconds) { mutableStateOf(range.endEpochSeconds.takeIf { it > 0L }?.toString().orEmpty()) }
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Text("可选：输入 Unix 秒时间戳；留空表示不限。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    LabeledTextField(start, {
        start = it.filter(Char::isDigit)
        onChange(range.copy(startEpochSeconds = start.toLongOrNull() ?: 0L))
    }, "开始时间")
    LabeledTextField(end, {
        end = it.filter(Char::isDigit)
        onChange(range.copy(endEpochSeconds = end.toLongOrNull() ?: 0L))
    }, "结束时间")
}

@Composable
private fun WorkshopDetailScreen(item: WorkshopItem, wide: Boolean, onBack: () -> Unit) {
    ScreenList(wide) {
        item { SecondaryButton("返回创意工坊", onClick = onBack) }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WorkshopArtworkThumbnail(item, Modifier.fillMaxWidth().height(210.dp), ContentScale.Fit)
                    Text(item.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(item.authorName.ifBlank { "未知作者" }, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    item.shortDescription.takeIf(String::isNotBlank)?.let { Text(it, fontSize = 14.sp) }
                    Text(listOfNotNull(item.declaredSizeBytes?.let(::formatBytes), item.updatedAtEpochSeconds?.let { "已更新" }, item.tags.take(3).takeIf { it.isNotEmpty() }?.joinToString(" · ")).joinToString(" · "), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        item { NoticeStrip("关于此 Mod", item.description.ifBlank { "Steam 未提供更多说明。" }) }
        item { NoticeStrip("当前仅开放浏览", "登录与下载功能已隐藏；如需添加 Mod，请从本地导入 ZIP 文件。") }
        item { DiagnosticPanel("技术详情", "条目编号：${item.publishedFileId}\n访问方式：Steam 公开服务") }
    }
}

@Composable
private fun DownloadCenterScreen(state: ManagerUiState, actions: ManagerActions, wide: Boolean, onBack: () -> Unit, onShowDialog: (DialogKind) -> Unit) {
    ScreenList(wide) {
        item { SecondaryButton("返回获取 Mod", onClick = onBack) }
        item { HeroPanel("下载中心", "处理你的 Mod", "下载内容会先检查，只有你确认后才会加入“我的 Mod”。") }
        if (state.downloadTasks.isEmpty()) item { EmptyPanel("还没有下载任务", "从 Mod 详情选择“下载并检查”后，进度会显示在这里。") }
        downloadGroups(state.downloadTasks).forEach { (title, tasks) ->
            if (tasks.isNotEmpty()) {
                item { SectionLabel(title, "${tasks.size} 项") }
                items(tasks, key = { it.id }) { task -> DownloadTaskCard(task, actions, onShowDialog) }
            }
        }
    }
}

private fun downloadGroups(tasks: List<DownloadTask>): List<Pair<String, List<DownloadTask>>> = listOf(
    "需要你确认" to tasks.filter { it.stage == DownloadStage.AwaitingImportConfirmation },
    "正在处理" to tasks.filter { it.stage in setOf(DownloadStage.Queued, DownloadStage.ResolvingMetadata, DownloadStage.AwaitingPublicUrl, DownloadStage.Downloading, DownloadStage.Verifying, DownloadStage.Importing) },
    "需要处理" to tasks.filter { it.stage in setOf(DownloadStage.Paused, DownloadStage.NeedsLogin, DownloadStage.Failed) },
    "已完成" to tasks.filter { it.stage in setOf(DownloadStage.Imported, DownloadStage.Cancelled) },
)

@Composable
private fun DownloadTaskCard(task: DownloadTask, actions: ManagerActions, onShowDialog: (DialogKind) -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.title.ifBlank { "创意工坊 Mod" }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(downloadStatus(task), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            when (task.stage) {
                DownloadStage.AwaitingImportConfirmation -> PrimaryButton("检查并添加") { actions.confirmWorkshopImport(task.id) }
                DownloadStage.Paused -> PrimaryButton("继续下载") { actions.resumeWorkshopDownload(task.id) }
                DownloadStage.NeedsLogin, DownloadStage.Failed -> PrimaryButton("重试") { actions.retryWorkshopDownload(task.id) }
                DownloadStage.Imported, DownloadStage.Cancelled -> Unit
                else -> PrimaryButton("暂停下载") { actions.pauseWorkshopDownload(task.id) }
            }
            if (task.stage == DownloadStage.AwaitingImportConfirmation) SecondaryButton("丢弃下载内容") { actions.discardWorkshopArtifact(task.id) }
            if (task.stage !in setOf(DownloadStage.Imported, DownloadStage.Cancelled, DownloadStage.Importing)) SecondaryButton("删除任务") { onShowDialog(DialogKind.WorkshopTaskRemoval(task.id)) }
        }
    }
}

@Composable
private fun MyModsScreen(state: ManagerUiState, actions: ManagerActions, wide: Boolean, onShowDialog: (DialogKind) -> Unit) {
    val syncStatus = state.gameModSync
    val activationRequired = syncStatus?.availability == GameModSyncAvailability.ActivationRequired
    val externalMods = syncStatus?.mods.orEmpty().filterNot { it.managedByManager }
    ScreenList(wide) {
        item {
            HeroPanel(
                eyebrow = "同步给游戏",
                title = "管理 Mod",
                body = when {
                    state.gameModSyncInProgress -> "正在同步 Mod 到游戏目录…"
                    activationRequired -> "请先启动游戏并保持在后台，返回此处会自动继续同步。"
                    syncStatus?.isReady == true -> "Manager 只管理同步到游戏目录。加载、热开关和排序请在游戏内官方 Mod 面板完成。"
                    syncStatus != null -> syncStatus.reason ?: "暂时无法读取游戏 Mod 目录。"
                    else -> "正在检查游戏 Mod 目录…"
                },
                action = when {
                    activationRequired -> "启动游戏"
                    syncStatus?.isReady != true -> "重新检查"
                    else -> null
                },
                actionEnabled = !state.gameModSyncInProgress && !state.cachedModDeletionInProgress,
                onAction = when {
                    activationRequired -> actions.launchGameForModSync
                    syncStatus?.isReady != true -> actions.refreshGameMods
                    else -> null
                },
            )
        }
        item { ImportButton("从本地添加 Mod", onClick = actions.importMod) }
        item {
            PrimaryButton(
                "合并 Mod",
                enabled = !state.gameModSyncInProgress &&
                    !state.cachedModDeletionInProgress,
                onClick = actions.openMerge,
            )
        }
        item {
            PrimaryButton(
                "导出/分享 Mod",
                enabled = state.cachedMods.isNotEmpty() &&
                    !state.gameModSyncInProgress &&
                    !state.cachedModDeletionInProgress &&
                    state.modExport.operation is com.sultansgame.modmanager.ModExportOperation.Idle,
                onClick = actions.openModExport,
            )
        }
        item { NoticeStrip("请在游戏内管理Mod", "管理器只负责游戏 Mod 目录；请在游戏内官方 Mod 面板(主界面左边从上往下第五个按钮)刷新、启用Mod。") }
        item { SectionLabel("Manager 管理的 Mod", "${state.gameModSyncItems.size} 个") }
        if (state.gameModSyncItems.isEmpty()) {
            item { EmptyPanel("还没有 Mod", "你可以从创意工坊下载，或从本地选择 ZIP 文件导入。") }
        }
        items(state.gameModSyncItems, key = GameModSyncItem::cacheKey) { item ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                syncItemStatus(item, state),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        StatusPill(if (item.syncedToGame) "同步给游戏" else "未同步")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val enabled = !state.gameModSyncInProgress && !state.cachedModDeletionInProgress
                        SmallAction(if (item.syncedToGame) "从游戏中移除" else "同步给游戏", enabled) {
                            actions.setModSyncedToGame(item.cacheKey, !item.syncedToGame)
                        }
                        SmallAction("重命名", enabled) { onShowDialog(DialogKind.RenameCachedMod(item.cacheKey)) }
                        SmallAction("删除 Mod", enabled) { onShowDialog(DialogKind.DeleteCachedMod(item.cacheKey)) }
                    }
                }
            }
        }
        if (externalMods.isNotEmpty()) {
            item { SectionLabel("游戏中的其他 Mod", "${externalMods.size} 个") }
            item { NoticeStrip("直接加入的 Mod", "这些文件夹由其他方式写入游戏目录。Manager 会显示它们，但不会修改、接管或删除。") }
            items(externalMods, key = { it.directoryName }) { mod ->
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(mod.directoryName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("加载、热开关和排序请在游戏内官方 Mod 面板完成。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}

private fun syncItemStatus(item: GameModSyncItem, state: ManagerUiState): String {
    val pending = state.pendingGameModSyncOperations.firstOrNull { it.cacheKey == item.cacheKey }
    return when {
        pending != null && pending.type == com.sultansgame.modmanager.model.GameModSyncOperationType.Sync -> "等待同步到游戏"
        pending != null -> "等待从游戏中移除"
        item.syncedToGame -> "已同步到游戏目录"
        else -> "未同步到游戏目录"
    }
}

@Composable
private fun SettingsScreen(state: ManagerUiState, actions: ManagerActions, wide: Boolean, onShowDialog: (DialogKind) -> Unit) {
    ScreenList(wide) {
        item { HeroPanel("", "设置", "") }
        item { SectionLabel("存储", "${state.cachedMods.size} 个 Mod") }
        item { ListPanel("清理本地 Mod ", "存储空间管理", "管理") { onShowDialog(DialogKind.ClearCache) } }
        item { ListPanel("重置管理器状态", "导入或修补出现异常时可尝试；保留已缓存的 Mod 和设备签名密钥", "重置") { onShowDialog(DialogKind.ResetManagerState) } }
        item { SectionLabel("创意工坊", "可选") }
        item {
            val enabled = state.showWorkshop
            ConfirmationCheckbox("开启创意工坊", enabled == true, enabled != null) { actions.setWorkshopEnabled(it) }
            Text(if (enabled == null) "正在读取创意工坊显示设置…" else "开启后，创意工坊会显示在导航栏中；默认关闭。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        item { SectionLabel("应用更新", "GitHub") }
        item {
            val enabled = state.autoUpdateCheckEnabled
            ConfirmationCheckbox(
                "启动时检查 GitHub 更新",
                checked = enabled == true,
                onToggle = { actions.setAutoUpdateCheckEnabled(it) },
                enabled = enabled != null,
            )
            Text(
                if (enabled == null) "正在读取更新检查设置…" else "关闭后，应用启动时不会联网检查新版本。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        item { SectionLabel("帮助与安全", "") }
        item { ListPanel("本项目仅供学习交流使用", "请勿用于非法用途", "查看") { onShowDialog(DialogKind.Notice) } }
        item { ListPanel("开源许可", "GNU GPLv3", "查看") { onShowDialog(DialogKind.License) } }
        item { DiagnosticPanel("应用诊断", "游戏：${gameProbeDiagnostic(state.gameProbeResult)}\n已添加 Mod：${state.cachedMods.size}\n下载任务：${state.downloadTasks.size}") }
    }
}


@Composable
private fun ModExportScreen(state: ManagerUiState, actions: ManagerActions, wide: Boolean) {
    val export = state.modExport
    val selected = export.selectedCacheKeys.toSet()
    val allSelected = state.cachedMods.isNotEmpty() && selected.size == state.cachedMods.size && selected.containsAll(state.cachedMods.map { it.cacheKey })
    val busy = export.operation !is com.sultansgame.modmanager.ModExportOperation.Idle
    LaunchedEffect(state.cachedMods, export.isOpen) {
        val valid = state.cachedMods.map { it.cacheKey }.toSet()
        val filtered = export.selectedCacheKeys.filter(valid::contains)
        if (filtered != export.selectedCacheKeys) actions.setModExportSelection(filtered)
    }
    ScreenList(wide) {
        item { HeroPanel("导出/分享", "选择 Mod", "选择一个或多个 Mod，ZIP 中每个 Mod 会保留独立的顶层目录。", "返回管理 Mod", !busy, actions.closeModExport) }
        item { SectionLabel("已选择 Mod", "${selected.size} / ${state.cachedMods.size}") }
        item { SecondaryButton(if (allSelected) "取消全选" else "一键全选", !busy && state.cachedMods.isNotEmpty(), actions.selectAllModExport) }
        items(state.cachedMods, key = { "export-${it.cacheKey}" }) { mod ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) { ConfirmationCheckbox(mod.displayName, mod.cacheKey in selected, !busy) { actions.toggleModExport(mod.cacheKey) } }
        }
        when (val operation = export.operation) {
            is com.sultansgame.modmanager.ModExportOperation.Compressing -> item { NoticeStrip("正在生成 ZIP", "${operation.completedFiles} / ${operation.totalFiles} 个文件 · ${formatBytes(operation.writtenBytes)} / ${formatBytes(operation.totalBytes)}") }
            is com.sultansgame.modmanager.ModExportOperation.SelectingDestination -> item { LoadingPanel("正在选择导出位置…") }
            is com.sultansgame.modmanager.ModExportOperation.Writing -> item { NoticeStrip("正在保存 ZIP", "${formatBytes(operation.writtenBytes)} / ${formatBytes(operation.totalBytes)}") }
            is com.sultansgame.modmanager.ModExportOperation.Sharing -> item { LoadingPanel("正在打开分享面板…") }
            com.sultansgame.modmanager.ModExportOperation.Idle -> Unit
        }
        item { PrimaryButton("分享到其他应用", selected.isNotEmpty() && !busy) { actions.requestModExport(com.sultansgame.modmanager.ModExportAction.Share) } }
        item { SecondaryButton("导出到本地", selected.isNotEmpty() && !busy) { actions.requestModExport(com.sultansgame.modmanager.ModExportAction.SaveToLocal) } }
    }
}

@Composable
private fun ModExportSettingsDialog(suggestedFileName: String, action: com.sultansgame.modmanager.ModExportAction, onSubmit: (String, CharArray) -> Unit, onCancel: () -> Unit) {
    var fileName by remember(suggestedFileName) { mutableStateOf(suggestedFileName) }
    var password by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { password = ""; onCancel() }) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("导出 Mod ZIP", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                LabeledTextField(fileName, { fileName = it }, "ZIP 文件名")
                LabeledTextField(password, { password = it }, "压缩包密码（可留空）", password = true)
                PrimaryButton(if (action == com.sultansgame.modmanager.ModExportAction.Share) "分享到其他应用" else "导出到本地", fileName.isNotBlank()) {
                    val supplied = password.toCharArray()
                    password = ""
                    onSubmit(fileName, supplied)
                }
                SecondaryButton("取消") { password = ""; onCancel() }
            }
        }
    }
}


@Composable
private fun DialogHost(state: ManagerUiState, actions: ManagerActions, dialog: DialogKind?, onDismiss: () -> Unit) {
    when (dialog) {
        DialogKind.Notice -> LegalNoticeDialog(actions.acceptNotice, onDismiss)
        DialogKind.Privacy -> TextDialog("隐私与数据流", "你选择导入的 Mod、下载暂存和修补工件保存在应用私有目录。浏览创意工坊时只会连接 Steam 公开服务和经过校验的下载地址。密码和 Steam Guard 验证码只用于认证；选择记住登录状态时，刷新令牌会由 Android Keystore 加密保存。", onDismiss)
        DialogKind.License -> TextDialog("开源许可", "本项目以 GNU GPLv3 开源", onDismiss)
        DialogKind.ClearCache -> ConfirmDialog("清理本地 Mod？", "这会删除管理器所有已添加的 Mod，并安排从游戏 Mod 目录中移除对应内容。", "确认清理", { actions.clearModCache(); onDismiss() }, onDismiss)
        DialogKind.ResetManagerState -> ConfirmDialog(
            "重置管理器状态？",
            "这会清除导入记录、临时文件、下载任务、登录信息和管理器设置，并取消管理器记录的未完成安装事务；会保留已缓存的 Mod 与设备签名密钥。不会卸载或直接修改当前已安装的游戏，未完成的修补安装也不会继续由管理器恢复。",
            "确认重置",
            { actions.resetManagerState(); onDismiss() },
            onDismiss,
        )
        is DialogKind.DeleteCachedMod -> {
            val item = state.gameModSyncItems.firstOrNull { it.cacheKey == dialog.cacheKey }
            if (item != null) ConfirmDialog("删除 ${item.displayName}？", "这会从管理器和游戏 Mod 目录移除对应Mod。", "删除 Mod", { actions.deleteCachedMod(item.cacheKey); onDismiss() }, onDismiss)
        }
        is DialogKind.RenameCachedMod -> {
            val item = state.gameModSyncItems.firstOrNull { it.cacheKey == dialog.cacheKey }
            if (item != null) RenameCachedModDialog(item.displayName, actions.renameCachedMod, item.cacheKey, onDismiss)
        }
        is DialogKind.DeviceInstallRisk -> DeviceInstallRiskDialog(dialog.warning, onDismiss)
        DialogKind.UpdateAvailable -> {
            state.availableUpdate?.let { update ->
                UpdateAvailableDialog(
                    update = update,
                    onOpen = {
                        actions.openAvailableUpdate()
                        actions.dismissAvailableUpdate()
                        onDismiss()
                    },
                    onDismiss = {
                        actions.dismissAvailableUpdate()
                        onDismiss()
                    },
                )
            }
        }
        DialogKind.PatchCleanup -> {
            state.patchCleanup?.let { cleanup ->
                ConfirmDialog(
                    "清理临时文件？",
                    "这会删除因修补游戏而产生的临时文件，释放 ${formatBytes(cleanup.sizeBytes)}。此操作不会删除 Mod 或已导出的 APKS。安装过程中请勿清理临时文件。",
                    "确认清理",
                    { actions.confirmPatchCleanup(); onDismiss() },
                    { actions.dismissPatchCleanup(); onDismiss() },
                )
            }
        }
        is DialogKind.WorkshopTaskRemoval -> {
            val task = state.downloadTasks.firstOrNull { it.id == dialog.taskId }
            if (task != null) ConfirmDialog("删除下载任务？", "这会停止任务并删除应用内下载暂存", "删除任务", { actions.removeWorkshopDownload(task.id); onDismiss() }, onDismiss)
        }
        DialogKind.ExternalZipImport -> state.pendingExternalZip?.let { request ->
            if (state.pendingZipPassword) {
                ZipPasswordDialog(
                    request.displayName,
                    state.zipImportInProgress,
                    actions.submitZipPassword,
                    actions.cancelExternalZipImport,
                    onDismiss,
                )
            } else {
                ConfirmDialog(
                    "导入外部 ZIP？",
                    "将检查 ${request.displayName} 并把通过校验的 Mod 安全缓存到应用内；不会自动修改游戏。",
                    "检查并导入",
                    { actions.confirmExternalZipImport(); onDismiss() },
                    { actions.cancelExternalZipImport(); onDismiss() },
                )
            }
        }
        DialogKind.ZipPasswordImport -> state.pendingExternalZip?.let { request ->
            ZipPasswordDialog(
                request.displayName,
                state.zipImportInProgress,
                actions.submitZipPassword,
                actions.cancelExternalZipImport,
                onDismiss,
            )
        }
        DialogKind.MergeSyncConfirmation -> MergeSyncConfirmationDialog(actions, onDismiss)
        is DialogKind.ModExportSettings -> ModExportSettingsDialog(
            state.modExport.suggestedFileName,
            dialog.action,
            { fileName, password -> actions.submitModExport(fileName, password); onDismiss() },
            { actions.cancelModExportSettings(); onDismiss() },
        )
        null -> Unit
    }
}

@Composable
private fun MergeSyncConfirmationDialog(actions: ManagerActions, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Mod 已完成合并", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("是否停止参与合并的原始 Mod 同步？同时同步原始 Mod 和合成 Mod 可能导致内容重复应用。", fontSize = 14.sp)
                PrimaryButton("停止原始 Mod 同步", onClick = { actions.stopOriginalSync(); onDismiss() })
                SecondaryButton("保持原始 Mod 同步", onClick = { actions.keepOriginalSync(); onDismiss() })
            }
        }
    }
}


@Composable
private fun MergeModsScreen(state: ManagerUiState, actions: ManagerActions, wide: Boolean) {
    val merge = state.merge
    val selected = merge.selectedCacheKeys.toSet()
    val preflightReady = merge.preflight is MergePreflightState.Ready
    ScreenList(wide) {
        item {
            MergeHeroPanel(onBack = actions.closeMerge)
        }
        merge.catalogError?.let { error -> item { NoticeStrip("无法读取合并 Catalog", error) } }
        merge.catalogSelection?.warning?.let { warning ->
            item { NoticeStrip("Catalog 警告", warning) }
        }
        item { SectionLabel("选择参与合并的 Mod", "${selected.size} 个") }
        items(state.cachedMods, key = { "merge-source-${it.cacheKey}" }) { mod ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                ConfirmationCheckbox(mod.displayName, mod.cacheKey in selected, enabled = !merge.isRunning) { actions.toggleMergeMod(mod.cacheKey) }
            }
        }
        if (merge.selectedCacheKeys.isNotEmpty()) {
            item { SectionLabel("本次合并顺序", "底部优先级最高") }
            items(
                merge.selectedCacheKeys.mapIndexed { index, key ->
                    index to state.cachedMods.firstOrNull { it.cacheKey == key }
                },
                key = { "merge-order-${it.second?.cacheKey ?: it.first}" },
            ) { (index, mod) ->
                mod?.let {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}. ${it.displayName}", Modifier.weight(1f))
                            SmallAction("上移", index > 0 && !merge.isRunning) { actions.moveMergeMod(index, index - 1) }
                            SmallAction("下移", index < merge.selectedCacheKeys.lastIndex && !merge.isRunning) { actions.moveMergeMod(index, index + 1) }
                        }
                    }
                }
            }
        }
        when (val preflight = merge.preflight) {
            MergePreflightState.Idle -> Unit
            is MergePreflightState.Running -> item { LoadingPanel("正在检查 Mod 冲突…") }
            is MergePreflightState.Failed -> item { NoticeStrip("预检失败", preflight.reason) }
            is MergePreflightState.Ready -> {
                preflight.result.warnings.forEach { warning ->
                    item { NoticeStrip("合并警告", warning.message) }
                }
                if (preflight.result.conflicts.isNotEmpty() &&
                    preflight.result.warnings.none { it.code == "id_conflict" }
                ) {
                    item {
                        NoticeStrip(
                            "检测到 ID 冲突",
                            "${preflight.result.conflicts.size} 个冲突已继续尝试重映射；部分引用可能无法完全对应。",
                        )
                    }
                }
            }
        }
        merge.progress?.let { progress -> item { LoadingPanel(progress) } }
        item {
            LabeledTextField(
                merge.resultDisplayName,
                actions.setMergeDisplayName,
                "Manager 中的显示名称",
            )
        }
        item { PrimaryButton(if (merge.isRunning) "正在合并…" else "开始合并", preflightReady && !merge.isRunning, actions.startMerge) }
        if (merge.resultCacheKey != null) {
            item { NoticeStrip("合并完成", "结果已加入 Manager 缓存。") }
        }
    }
}

@Composable
private fun MergeHeroPanel(onBack: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer),
        insideMargin = PaddingValues(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("合并 Mod", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("生成合成 Mod", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "选择并排序 Mod。列表底部优先级最高；结果会作为普通 Mod 加入 Manager。\n" +
                    "合并mod功能参考复用了 @fentender 老师开发的mod合并管理器，但因安卓版无法提取游戏JSON，实际合并结果可能与上游工具有出入。如果有能力，请点击链接去给这位老师的仓库点亮颗star！",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            ExternalLinkText(MERGE_REFERENCE_URL)
            PrimaryButton("返回管理 Mod", onClick = onBack)
        }
    }
}

/** A tappable URL that opens in the browser; stays inert when none is installed. */
@Composable
internal fun ExternalLinkText(url: String) {
    val context = LocalContext.current
    Text(
        url,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.clickable {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE),
                )
            } catch (_: android.content.ActivityNotFoundException) {
                // No browser is available; keep the page usable.
            }
        },
    )
}

@Composable
private fun RenameCachedModDialog(
    initialName: String,
    onRename: (String, String) -> Unit,
    cacheKey: String,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val normalizedName = com.sultansgame.modmanager.storage.ModDisplayNamePolicy.normalize(name)
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("重命名 Mod", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(
                    "只修改 Mod 在管理器中的显示名称，不会改变 Mod 文件、缓存目录或游戏中的名称。",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                LabeledTextField(name, { name = it }, "Manager 中的显示名称")
                if (name.isNotEmpty() && normalizedName == null) {
                    Text("名称不能为空。", fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                }
                PrimaryButton("保存", normalizedName != null) {
                    onRename(cacheKey, requireNotNull(normalizedName))
                    onDismiss()
                }
                SecondaryButton("取消", onClick = onDismiss)
            }
        }
    }
}

private const val MERGE_REFERENCE_URL = "https://github.com/fentender/sutan-game"

@Composable
private fun ZipPasswordDialog(
    displayName: String,
    busy: Boolean,
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { if (!busy) { password = ""; onDismiss() } }) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("输入 ZIP 密码", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("$displayName 已加密", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                LabeledTextField(password, { password = it }, "ZIP 密码", password = true)
                if (busy) LoadingPanel("正在解压并校验 ZIP")
                PrimaryButton("检查并导入", password.isNotEmpty() && !busy) {
                    val supplied = password.toCharArray()
                    password = ""
                    onSubmit(supplied)
                }
                SecondaryButton("取消", !busy) {
                    password = ""
                    onCancel()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SteamLoginDialog(auth: SteamAuthState, actions: ManagerActions, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var guardCode by remember { mutableStateOf("") }
    var rememberSession by rememberSaveable { mutableStateOf(false) }
    val busy = auth is SteamAuthState.SigningIn || auth is SteamAuthState.VerifyingSteamGuard
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (auth) {
                    is SteamAuthState.SignedIn -> {
                        Text("已登录 Steam", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(auth.accountName, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        PrimaryButton("退出 Steam") { actions.logoutSteam(); onDismiss() }
                    }
                    is SteamAuthState.SteamGuardRequired -> {
                        Text("需要 Steam Guard 验证", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("请在 Steam 中获取验证码并提交。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        LabeledTextField(guardCode, { guardCode = it }, "验证码")
                        PrimaryButton("提交验证码", guardCode.isNotBlank()) { actions.submitSteamGuard(guardCode); guardCode = "" }
                    }
                    is SteamAuthState.SteamAuthStatusUnknown, is SteamAuthState.AwaitingConfirmation -> {
                        Text("等待 Steam 确认", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("请在 Steam 完成确认后继续检查。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        PrimaryButton("继续检查", onClick = actions.checkPendingSteamLogin)
                    }
                    SteamAuthState.SigningIn, is SteamAuthState.VerifyingSteamGuard -> LoadingPanel("正在连接 Steam")
                    else -> {
                        Text("登录 Steam", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("登录即可下载创意工坊中的项目", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        LabeledTextField(username, { username = it }, "Steam 账号")
                        LabeledTextField(password, { password = it }, "Steam 密码", password = true)
                        ConfirmationCheckbox("记住登录状态，以后自动登录", rememberSession) { rememberSession = it }
                        PrimaryButton("登录", username.isNotBlank() && password.isNotBlank()) { actions.beginSteamLogin(username, password, rememberSession); password = "" }
                    }
                }
                if (!busy) SecondaryButton("关闭", onClick = onDismiss)
            }
        }
    }
}

@Composable
internal fun ScreenList(wide: Boolean, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (wide) 34.dp else 18.dp, vertical = if (wide) 10.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
internal fun HeroPanel(eyebrow: String, title: String, body: String, action: String? = null, actionEnabled: Boolean = true, onAction: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer), insideMargin = PaddingValues(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(eyebrow, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(body, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (action != null && onAction != null) PrimaryButton(action, actionEnabled, onAction)
        }
    }
}

@Composable
internal fun SectionLabel(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        StatusPill(trailing)
    }
}

@Composable
private fun WorkshopIntroPanel(actions: ManagerActions) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer), insideMargin = PaddingValues(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("获取 Mod", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("创意工坊", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("浏览创意工坊；如需添加 Mod，请从本地导入。", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("创意工坊功能主要参考借鉴了 @cjtestuse 老师的项目。如果有能力，请点击链接给这位老师的仓库点亮颗star！", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(WORKSHOP_NATIVE_URL, fontSize = 14.sp, color = MiuixTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = actions.openWorkshopNative))
            PrimaryButton("点这里也可以从本地添加 Mod", onClick = actions.importMod)
        }
    }
}

@Composable
private fun WorkshopBrowseItemCard(item: WorkshopItem, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WorkshopArtworkThumbnail(item, Modifier.size(width = 104.dp, height = 70.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(item.authorName.takeIf(String::isNotBlank), item.declaredSizeBytes?.let(::formatBytes), item.tags.take(2).takeIf { it.isNotEmpty() }?.joinToString()).joinToString(" · ").ifBlank { "Steam 创意工坊 Mod" }, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            StatusPill(if (item.canDownload) "查看" else "不可用")
        }
    }
}

@Composable
private fun WorkshopArtworkThumbnail(item: WorkshopItem, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val candidateUrls = remember(item.previewUrl) {
        val primary = WorkshopHttpPolicy.normalizePreviewImageUrl(item.previewUrl)
        listOfNotNull(
            primary,
            primary?.takeIf { it.contains("/capsule_616x353.jpg") }
                ?.replace("/capsule_616x353.jpg", "/header.jpg")
                ?.let(WorkshopHttpPolicy::normalizePreviewImageUrl),
        ).distinct()
    }
    var index by remember(candidateUrls) { mutableIntStateOf(0) }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(item.title.firstOrNull()?.uppercase() ?: "M", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        candidateUrls.getOrNull(index)?.let { url ->
            AsyncImage(
                ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = "${item.title} 的创意工坊封面",
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { if (index < candidateUrls.lastIndex) index += 1 },
            )
        }
    }
}

@Composable
internal fun ListPanel(
    title: String,
    body: String,
    trailing: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(17.dp), onClick = { if (enabled) onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(body, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            StatusPill(trailing)
        }
    }
}

@Composable
internal fun NoticeStrip(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(body, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun DiagnosticPanel(title: String, details: String) {
    var expanded by rememberSaveable(title, details) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant), insideMargin = PaddingValues(14.dp), onClick = { expanded = !expanded }) {
        Text(if (expanded) "收起 $title" else title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (expanded) Text(details.ifBlank { "没有更多信息。" }, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
internal fun EmptyPanel(title: String, body: String) = NoticeStrip(title, body)

@Composable
internal fun LoadingPanel(body: String, title: String = "正在处理") {
    Card(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(12.dp))
            Crossfade(targetState = title to body, label = "loading-panel-content") { (currentTitle, currentBody) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(currentTitle, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(currentBody, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
private fun FriendlyErrorPanel(title: String, summary: String, diagnostics: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.errorContainer), insideMargin = PaddingValues(17.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(summary, fontSize = 13.sp)
            DiagnosticPanel("查看诊断信息", diagnostics)
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(MiuixTheme.colorScheme.surfaceVariant).padding(horizontal = 9.dp, vertical = 5.dp)) {
        Text(text, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
internal fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    PrimaryButtonContent(
        label = label,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InlinePrimaryButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    PrimaryButtonContent(
        label = label,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun PrimaryButtonContent(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Card(
        modifier.semantics { if (!enabled) disabled() },
        colors = CardDefaults.defaultColors(
            color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant,
        ),
        insideMargin = PaddingValues(horizontal = 15.dp, vertical = 12.dp),
        onClick = { if (enabled) onClick() },
    ) {
        Text(
            label,
            Modifier.fillMaxWidth(),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ImportButton(label: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryVariant),
        insideMargin = PaddingValues(horizontal = 15.dp, vertical = 12.dp),
        onClick = onClick,
    ) {
        Text(
            label,
            Modifier.fillMaxWidth(),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onPrimaryVariant,
        )
    }
}

@Composable
internal fun SecondaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant), insideMargin = PaddingValues(horizontal = 15.dp, vertical = 11.dp), onClick = { if (enabled) onClick() }) {
        Text(label, Modifier.fillMaxWidth(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
internal fun SmallAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (enabled) MiuixTheme.colorScheme.primaryVariant else MiuixTheme.colorScheme.surfaceVariant).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp)) {
        Text(label, fontSize = 12.sp, color = if (enabled) MiuixTheme.colorScheme.onPrimaryVariant else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
internal fun LabeledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    password: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(14.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp),
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        )
    }
}

@Composable
internal fun ConfirmationCheckbox(label: String, checked: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(6.dp)).background(if (checked) MiuixTheme.colorScheme.primary else Color.Transparent), contentAlignment = Alignment.Center) {
            if (checked) Text("✓", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
    }
}

@Composable
private fun ApksExportAction(export: ApksExportUiState, transactionId: String, onExport: (String) -> Unit) {
    when (export) {
        ApksExportUiState.Idle -> PrimaryButton("导出安装包（APKS）") { onExport(transactionId) }
        is ApksExportUiState.SelectingDestination -> if (export.transactionId == transactionId) LoadingPanel("正在选择导出位置…")
        is ApksExportUiState.Validating -> if (export.transactionId == transactionId) LoadingPanel("正在检查安装文件…")
        is ApksExportUiState.Writing -> if (export.transactionId == transactionId) {
            val progress = if (export.totalBytes > 0L) (export.writtenBytes.toFloat() / export.totalBytes).coerceIn(0f, 1f) else 0f
            NoticeStrip("正在导出", "${formatBytes(export.writtenBytes)} / ${formatBytes(export.totalBytes)} · ${(progress * 100).toInt()}%")
        }
    }
}

@Composable
private fun FeedbackBanner(feedback: FeedbackMessage, onDismiss: () -> Unit, wide: Boolean) {
    Box(Modifier.fillMaxSize().padding(start = if (wide) 300.dp else 16.dp, end = 16.dp, top = if (wide) 18.dp else 72.dp), contentAlignment = Alignment.TopEnd) {
        Card(Modifier.width(340.dp), colors = CardDefaults.defaultColors(color = if (feedback.isError) MiuixTheme.colorScheme.errorContainer else MiuixTheme.colorScheme.secondaryContainer), insideMargin = PaddingValues(13.dp), onClick = onDismiss) { Text(feedback.text, fontSize = 13.sp) }
    }
}

@Composable
private fun PreparingNoticeDialog() {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) { Text("正在准备使用说明…", fontSize = 15.sp) }
    }
}

@Composable
private fun LegalNoticeDialog(onAccept: () -> Unit, onDismiss: (() -> Unit)? = null) {
    var checked by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { onDismiss?.invoke() }, properties = DialogProperties(dismissOnBackPress = onDismiss != null, dismissOnClickOutside = onDismiss != null)) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("使用前说明", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("本工具出于个人学习目的制作，与苏丹的游戏的官方开发商、发行商及任何相关关联公司无任何关系", fontSize = 14.sp)
                Text("", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                ConfirmationCheckbox("我已阅读并理解", checked) { checked = it }
                PrimaryButton("确认", checked) { onAccept(); onDismiss?.invoke() }
                onDismiss?.let { SecondaryButton("取消", onClick = it) }
            }
        }
    }
}

@Composable
private fun DeviceInstallRiskDialog(warning: DeviceInstallWarning, onDismiss: () -> Unit) {
    var canDismiss by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(DEVICE_INSTALL_RISK_READ_DELAY_MILLIS)
        canDismiss = true
    }

    val (title, body) = when (warning) {
        DeviceInstallWarning.Xiaomi -> "小米设备安装提示" to
            "由于 MIUI / 澎湃系统对 Android API 的修改，安装过程可能出现无法预知的情况并导致失败。如果遇到安装失败，大概率可以通过系统设置的开发者选项关闭 MIUI 优化/系统优化来修复"
        DeviceInstallWarning.OppoOnePlus -> "OPPO / 一加设备安装提示" to
            "由于 ColorOS 系统对 Android API 的修改，安装过程可能出现无法预知的情况并导致失败。如果遇到安装失败，大概率可以通过安装 CtsPermissionApp 来解决（此应用会使部分系统行为产生变化，建议您在安装完游戏后就卸载 CtsPermissionApp）"
    }

    Dialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = canDismiss,
            dismissOnClickOutside = canDismiss,
        ),
    ) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(
                    body,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    if (canDismiss) "已阅读 5 秒，现在可以关闭。" else "请阅读上方提示，5 秒后可以关闭。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                PrimaryButton(if (canDismiss) "关闭" else "请阅读（5 秒）", canDismiss, onDismiss)
            }
        }
    }
}

private const val DEVICE_INSTALL_RISK_READ_DELAY_MILLIS = 5_000L

@Composable
private fun TextDialog(title: String, body: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(body, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                PrimaryButton("关闭", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun UpdateAvailableDialog(
    update: com.sultansgame.modmanager.AvailableUpdate,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("发现新版本", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("最新版本：${update.version}", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                update.name.takeIf { it != update.version }?.let { Text(it, fontSize = 14.sp) }
                update.notes.takeIf(String::isNotBlank)?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                PrimaryButton("前往下载", onClick = onOpen)
                SecondaryButton("暂不更新", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(body, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                PrimaryButton(confirmLabel, onClick = onConfirm)
                SecondaryButton("取消", onClick = onDismiss)
            }
        }
    }
}

private fun gameProbeDiagnostic(result: GameProbeResult?): String = when (result) {
    null -> "正在检查游戏状态"
    GameProbeResult.NotInstalled -> "未检测到已安装游戏"
    is GameProbeResult.Found -> "已检测到游戏：${result.snapshot.packageName}"
    is GameProbeResult.Failed -> result.reason
}

private fun downloadStatus(task: DownloadTask): String {
    val stage = when (task.stage) {
        DownloadStage.Queued -> "已加入下载队列"
        DownloadStage.ResolvingMetadata -> "正在获取 Mod 信息"
        DownloadStage.AwaitingPublicUrl -> "正在准备下载"
        DownloadStage.Downloading -> "正在下载"
        DownloadStage.Paused -> "下载已暂停"
        DownloadStage.Verifying -> "正在检查下载内容"
        DownloadStage.AwaitingImportConfirmation -> "下载完成，等待你检查并添加"
        DownloadStage.Importing -> "正在添加 Mod"
        DownloadStage.Imported -> "已添加到我的 Mod"
        DownloadStage.NeedsLogin -> "需要重新登录 Steam"
        DownloadStage.Failed -> "下载未完成"
        DownloadStage.Cancelled -> "已取消"
    }
    val progress = task.totalBytes?.let { " · ${formatBytes(task.downloadedBytes)} / ${formatBytes(it)}" }.orEmpty()
    val failure = when (task.failure) {
        DownloadFailureCode.LoginRequired -> "：需要登录 Steam"
        DownloadFailureCode.NotOwnedOrUnavailable -> "：内容不可用或账号无权访问"
        DownloadFailureCode.InvalidArtifact, DownloadFailureCode.ChecksumMismatch, DownloadFailureCode.SizeMismatch -> "：下载内容未通过检查"
        DownloadFailureCode.UnsafeUrl -> "：下载地址不符合安全要求"
        null -> ""
        else -> "：可稍后重试"
    }
    return "$stage$progress$failure"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}
