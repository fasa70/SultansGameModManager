package com.sultansgame.modmanager.ui

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
import com.sultansgame.modmanager.PatchUiState
import com.sultansgame.modmanager.PreparedPatchRecovery
import com.sultansgame.modmanager.WorkshopUiState
import com.sultansgame.modmanager.model.Compatibility
import com.sultansgame.modmanager.model.DeviceSigningKeyState
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.GameModStorageStatus
import com.sultansgame.modmanager.model.ModStorageAvailability
import com.sultansgame.modmanager.model.ModStorageFailureCode
import com.sultansgame.modmanager.model.PatchConfirmation
import com.sultansgame.modmanager.model.SteamAuthState
import com.sultansgame.modmanager.model.WorkshopBrowseQuery
import com.sultansgame.modmanager.model.WorkshopDateRangeFilter
import com.sultansgame.modmanager.model.WorkshopBrowseTagGroupSelectionMode
import com.sultansgame.modmanager.model.WorkshopItem
import com.sultansgame.modmanager.platform.game.GameProbeResult
import com.sultansgame.modmanager.workshop.WorkshopHttpPolicy
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
    val launchGame: () -> Unit,
    val setModEnabled: (String, Boolean) -> Unit,
    val moveMod: (String, Int) -> Unit,
    val syncMods: (Boolean) -> Unit,
    val confirmStopGameAndSync: () -> Unit,
    val dismissStopGameAndSync: () -> Unit,
    val deleteCachedMod: (String) -> Unit,
    val clearModCache: () -> Unit,
    val acceptNotice: () -> Unit,
    val clearFeedback: () -> Unit,
)

private enum class Destination(val title: String, val caption: String) {
    Start("开始", "准备游戏"),
    Acquire("获取 Mod", "浏览与添加"),
    Library("我的 Mod", "同步并开始"),
    Settings("设置", "帮助与存储"),
}

private sealed interface DialogKind {
    data object Notice : DialogKind
    data object Privacy : DialogKind
    data object License : DialogKind
    data object ClearCache : DialogKind
    data class DeleteCachedMod(val cacheKey: String) : DialogKind
    data object SyncMods : DialogKind
    data object StopGameAndSync : DialogKind
    data object PatchCleanup : DialogKind
    data class WorkshopTaskRemoval(val taskId: String) : DialogKind
}

@Composable
fun ManagerApp(state: ManagerUiState, actions: ManagerActions) {
    var destinationIndex by rememberSaveable { mutableIntStateOf(Destination.Start.ordinal) }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    val destination = Destination.entries[destinationIndex]

    LaunchedEffect(state.patchCleanupConfirmation != null) {
        if (state.patchCleanupConfirmation != null) dialog = DialogKind.PatchCleanup
    }
    LaunchedEffect(state.gameStopSyncConfirmation) {
        if (state.gameStopSyncConfirmation != null) dialog = DialogKind.StopGameAndSync
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SideRail(destinationIndex) { destinationIndex = it }
                MainContent(
                    modifier = Modifier.weight(1f),
                    destination = destination,
                    state = state,
                    actions = actions,
                    wide = true,
                    onSelectDestination = { destinationIndex = it },
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
                    onSelectDestination = { destinationIndex = it },
                    onShowDialog = { dialog = it },
                )
                BottomNavigation(destinationIndex) { destinationIndex = it }
            }
        }
        state.feedback?.let { FeedbackBanner(it, actions.clearFeedback, wide) }
    }

    when (state.noticeAccepted) {
        null -> PreparingNoticeDialog()
        false -> LegalNoticeDialog(actions.acceptNotice)
        true -> Unit
    }
    DialogHost(state, actions, dialog, onDismiss = { dialog = null })
}

@Composable
private fun SideRail(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(276.dp).background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 18.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("苏丹的游戏", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("MOD MANAGER", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("开始使用", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(8.dp))
        Destination.entries.forEachIndexed { index, item ->
            DestinationItem(item, selectedIndex == index) { onSelect(index) }
            Spacer(Modifier.height(4.dp))
        }
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
private fun BottomNavigation(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Destination.entries.forEachIndexed { index, destination ->
            val selected = selectedIndex == index
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (selected) MiuixTheme.colorScheme.primaryVariant else Color.Transparent)
                    .clickable { onSelect(index) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(destination.title, fontSize = 11.sp)
            }
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
    onSelectDestination: (Int) -> Unit,
    onShowDialog: (DialogKind) -> Unit,
) {
    Column(modifier) {
        if (wide) ContentHeader(destination)
        when (destination) {
            Destination.Start -> StartScreen(state, actions, wide, onSelectDestination)
            Destination.Acquire -> AcquireNavigation(state, actions, wide, onShowDialog)
            Destination.Library -> MyModsScreen(state, actions, wide, onShowDialog)
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
private fun StartScreen(state: ManagerUiState, actions: ManagerActions, wide: Boolean, onSelectDestination: (Int) -> Unit) {
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
                    { onSelectDestination(Destination.Acquire.ordinal) }
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
                        Text("继续前请确认", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        val review = state.patch as PatchUiState.Review
                        ConfirmationCheckbox("我了解这是非官方修改，已备份重要存档", review.confirmation.acknowledgedInstallRisk) {
                            actions.updatePatchConfirmation(review.confirmation.copy(acknowledgedInstallRisk = it))
                        }
                        ConfirmationCheckbox("我了解更换设备后可能需要重新准备游戏", review.confirmation.acknowledgedRecoveryLimit) {
                            actions.updatePatchConfirmation(review.confirmation.copy(acknowledgedRecoveryLimit = it))
                        }
                        ConfirmationCheckbox("我了解系统会要求先卸载旧版本，再确认安装", review.confirmation.acknowledgedReinstallRequirement) {
                            actions.updatePatchConfirmation(review.confirmation.copy(acknowledgedReinstallRequirement = it))
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
        state.patchCleanup?.let { cleanup ->
            item { SectionLabel("存储与清理", formatBytes(cleanup.sizeBytes)) }
            item {
                SecondaryButton(
                    "临时文件已占用${formatBytes(cleanup.sizeBytes)}，点击清理",
                    enabled = !state.patchCleanupInProgress,
                    onClick = actions.requestPatchCleanup,
                )
            }
        }
        item { DiagnosticPanel("诊断信息", presentation.diagnostics) }
    }
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

internal fun PatchUiState.toStartOperationStatus(): StartOperationStatus? = when (this) {
    is PatchUiState.Importing -> StartOperationStatus(
        title = "正在导入游戏安装文件",
        body = "$label 请不要关闭应用。",
    )
    is PatchUiState.Preparing -> StartOperationStatus(
        title = "正在准备修补文件",
        body = "正在安全处理 ${input.sourceLabel}，请不要关闭应用。",
    )
    is PatchUiState.SubmittingInstall -> StartOperationStatus(
        title = "正在请求系统安装",
        body = "请稍候，系统安装确认页面即将打开。",
    )
    is PatchUiState.AwaitingSystemInstall -> StartOperationStatus(
        title = "正在等待系统安装确认",
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
            title = if (found) "准备已安装的游戏" else "先准备游戏",
            body = if (found) "我们会先检查已安装游戏是否可以安全准备。之后仍需要你在系统页面确认卸载和安装。" else "选择游戏安装文件开始。应用只会处理已加入安全支持列表的版本。",
            primaryLabel = if (found) "使用已安装的游戏" else "选择游戏安装包",
            primaryEnabled = true,
            diagnostics = gameProbeDiagnostic(state.gameProbeResult),
            primaryAction = { actions -> if (found) actions.selectInstalledGame else actions.importLocalApk },
        )
    }
    is PatchUiState.Importing -> StartPresentation("正在检查游戏安装文件", "请不要关闭应用。检查完成后会告诉你下一步。", "正在检查…", false, diagnostics = patch.label, primaryAction = { {} })
    is PatchUiState.Review -> {
        val unsupported = patch.input.classification.compatibility.compatibility == Compatibility.Unsupported
        val confirmationReady = patch.confirmation.permits(patch.input.classification.mode)
        StartPresentation(
            title = if (unsupported) "此游戏版本暂不支持" else "确认后准备游戏",
            body = if (unsupported) "此版本尚未加入安全支持列表，应用不会继续修改或安装。请选择其他游戏安装文件。" else "已完成基本检查。确认以下事项后，应用会准备所需文件；不会自动卸载或安装游戏。",
            primaryLabel = if (unsupported) "选择其他安装包" else "继续准备",
            primaryEnabled = unsupported || confirmationReady,
            showConfirmations = !unsupported,
            diagnostics = "来源：${patch.input.sourceLabel}\n版本：${patch.input.versionLabel}\n安装组件：${patch.input.splitCount + 1}\n签名：${patch.input.signerSummary}\n${patch.input.classification.compatibility.reasons.joinToString("\n")}",
            primaryAction = { actions -> if (unsupported) actions.restartPatch else actions.preparePatch },
        )
    }
    is PatchUiState.Preparing -> StartPresentation("正在准备游戏", "正在安全处理安装文件，请不要关闭应用。", "正在准备…", false, diagnostics = "准备中的安装文件：${patch.input.sourceLabel}", primaryAction = { {} })
    is PatchUiState.AwaitingOriginalUninstall -> StartPresentation("请先卸载原游戏", "准备已完成。请在系统页面卸载当前游戏，返回后再继续安装。", "打开系统卸载页面", diagnostics = patch.summary, primaryAction = { actions -> { actions.requestOriginalUninstall(patch.transactionId) } })
    is PatchUiState.ReadyToInstall -> StartPresentation("可以安装 Mod 支持版游戏", "原游戏已卸载。请在 Android 系统页面确认安装；应用不会自动完成系统操作。", "打开系统安装确认", diagnostics = patch.summary, primaryAction = { actions -> { actions.installPreparedArtifacts(patch.transactionId) } })
    is PatchUiState.SubmittingInstall -> StartPresentation("正在打开系统安装", "请稍候，马上会转到系统安装确认。", "正在处理…", false, diagnostics = patch.transactionId, primaryAction = { {} })
    is PatchUiState.AwaitingInstallPermission -> StartPresentation("需要允许安装应用", "请在系统设置允许此应用安装游戏。返回后还需要你手动确认安装。", "前往系统设置", diagnostics = "准备事务：${patch.transactionId ?: "尚未创建"}", primaryAction = { it.openUnknownSourcesSettings })
    is PatchUiState.AwaitingSystemInstall -> StartPresentation("请在系统页面完成安装", "安装完成后回到这里，应用会核验游戏是否已准备好。", "等待系统确认", false, diagnostics = patch.transactionId, primaryAction = { {} })
    is PatchUiState.Completed -> StartPresentation("游戏已准备好", "现在可以浏览创意工坊，添加 Mod 后再同步并启动游戏。", "去获取 Mod", diagnostics = patch.transactionId, primaryAction = { {} })
    is PatchUiState.Failed -> StartPresentation("准备未完成", "这一步没有完成，游戏和 Mod 未被更改。请重新开始；仍有问题时可查看诊断信息。", "重新开始", diagnostics = patch.reason, primaryAction = { it.restartPatch })
}

@Composable
private fun StartPresentation.primaryAction(actions: ManagerActions): () -> Unit = primaryAction(actions)

@Composable
private fun ResumePatchCard(recovery: PreparedPatchRecovery, actions: ManagerActions) {
    NoticeStrip("继续未完成的准备", "发现上次已安全准备的安装文件。继续前会重新检查文件和设备状态。")
    PrimaryButton("继续准备", onClick = { actions.resumePreparedPatch(recovery.transactionId) })
}

@Composable
private fun AcquireNavigation(state: ManagerUiState, actions: ManagerActions, wide: Boolean, onShowDialog: (DialogKind) -> Unit) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "browse") {
        composable("browse") {
            AcquireModsScreen(state, actions, wide, onOpenQueue = { navController.navigate("queue") }, onOpenDetail = { id ->
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
                detail != null && detail.item.publishedFileId.toString() == id -> WorkshopDetailScreen(detail.item, state.downloadTasks, wide, actions, onBack = { actions.lookupWorkshop(""); navController.popBackStack() }, onOpenQueue = { navController.navigate("queue") })
                state.workshop is WorkshopUiState.Error -> ScreenList(wide) { item { FriendlyErrorPanel("暂时无法读取此 Mod", "请返回创意工坊重试。", (state.workshop as WorkshopUiState.Error).reason) } }
                else -> ScreenList(wide) { item { LoadingPanel("正在读取 Mod 信息…") } }
            }
        }
        composable("queue") { DownloadCenterScreen(state, actions, wide, onBack = { navController.popBackStack() }, onShowDialog = onShowDialog) }
    }
}

@Composable
private fun AcquireModsScreen(state: ManagerUiState, actions: ManagerActions, wide: Boolean, onOpenQueue: () -> Unit, onOpenDetail: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf(state.workshopBrowse.query.searchText) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var filterDraft by remember { mutableStateOf(state.workshopBrowse.query) }
    val signedIn = state.steamAuthState as? SteamAuthState.SignedIn
    LaunchedEffect(state.workshopBrowse.query) { filterDraft = state.workshopBrowse.query }
    LaunchedEffect(state.workshopBrowse.items.isEmpty(), state.workshopBrowse.error, state.workshopBrowse.hasLoadedOnce, state.workshopBrowse.isRefreshing) {
        if (state.workshopBrowse.items.isEmpty() && state.workshopBrowse.error == null && !state.workshopBrowse.hasLoadedOnce && !state.workshopBrowse.isRefreshing) {
            actions.browseWorkshop(WorkshopBrowseQuery())
        }
    }
    val submitSearch = { actions.browseWorkshop(state.workshopBrowse.query.copy(searchText = query, page = 1).normalized()) }
    ScreenList(wide) {
        item {
            HeroPanel("获取 Mod", "找到想要的 Mod", "浏览公开内容或从本地添加 ZIP。下载完成后仍会先检查，并由你决定是否添加。", action = "从本地添加 Mod", onAction = actions.importMod)
        }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("搜索创意工坊", modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        SmallAction(if (signedIn == null) "登录 Steam" else "退出 Steam") {
                            if (signedIn == null) showLogin = true else actions.logoutSteam()
                        }
                    }
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
                        SmallAction("下载 ${state.downloadTasks.count { it.stage !in setOf(DownloadStage.Imported, DownloadStage.Cancelled) }}") { onOpenQueue() }
                    }
                }
            }
        }
        state.workshopBrowse.error?.let { item { FriendlyErrorPanel("暂时无法获取创意工坊", "请检查网络后重试。", it) } }
        if (state.workshopBrowse.items.isEmpty() && state.workshopBrowse.isRefreshing) item { LoadingPanel("正在浏览公开 Mod…") }
        if (state.workshopBrowse.items.isNotEmpty()) {
            item { SectionLabel("推荐 Mod", "${state.workshopBrowse.totalCount} 项") }
            items(state.workshopBrowse.items, key = { it.publishedFileId.toString() }) { item -> WorkshopBrowseItemCard(item) { onOpenDetail(item.publishedFileId.toString()) } }
            if (state.workshopBrowse.hasMore) item { PrimaryButton(if (state.workshopBrowse.isLoadingMore) "正在加载…" else "加载更多", !state.workshopBrowse.isLoadingMore && !state.workshopBrowse.isRefreshing) { actions.browseWorkshop(state.workshopBrowse.query.copy(page = state.workshopBrowse.query.page + 1)) } }
        } else if (state.workshopBrowse.hasLoadedOnce && state.workshopBrowse.error == null) item { EmptyPanel("没有找到 Mod", "试试其他关键词或清除筛选条件。") }
    }
    if (showLogin) SteamLoginDialog(state.steamAuthState, actions) { showLogin = false }
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
                Text("只保留常用条件。更精细的 Steam 条件不会影响安全检查。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
private fun WorkshopDetailScreen(item: WorkshopItem, tasks: List<DownloadTask>, wide: Boolean, actions: ManagerActions, onBack: () -> Unit, onOpenQueue: () -> Unit) {
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
        item {
            val active = tasks.firstOrNull { it.publishedFileId == item.publishedFileId && it.stage !in setOf(DownloadStage.Imported, DownloadStage.Cancelled, DownloadStage.Failed) }
            when {
                active != null -> {
                    NoticeStrip("已在下载中心", downloadStatus(active))
                    PrimaryButton("查看下载", onClick = onOpenQueue)
                }
                item.canDownload -> PrimaryButton("下载并检查") { actions.queueWorkshopDownload(item) }
                else -> NoticeStrip("当前无法下载", "此内容没有可验证的下载方式。应用不会尝试绕过 Steam 的访问限制。")
            }
        }
        item { DiagnosticPanel("技术详情", "条目编号：${item.publishedFileId}\n下载方式：${if (item.canDirectDownload) "公开地址" else "Steam 内容"}") }
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
    val storageMessage = gameStorageMessage(state.gameModStorage)
    ScreenList(wide) {
        item {
            HeroPanel(
                eyebrow = "同步并开始",
                title = if (state.cachedMods.isEmpty()) "先添加一个 Mod" else "让 Mod 在游戏中生效",
                body = storageMessage.summary,
                action = storageMessage.actionLabel,
                actionEnabled = storageMessage.actionEnabled && !state.deploymentInProgress && !state.cachedModDeletionInProgress,
                onAction = when (storageMessage.action) {
                    LibraryAction.Import -> actions.importMod
                    LibraryAction.Launch -> actions.launchGame
                    LibraryAction.Sync -> { { onShowDialog(DialogKind.SyncMods) } }
                    LibraryAction.Refresh -> actions.refreshGameMods
                },
            )
        }
        item { ImportButton("从本地添加 Mod", onClick = actions.importMod) }
        item { SectionLabel("我的 Mod", "${state.deploymentPlan.size} 个") }
        if (state.deploymentPlan.isEmpty()) item { EmptyPanel("还没有 Mod", "你可以从创意工坊下载，或从本地选择 ZIP 文件。") }
        items(state.deploymentPlan, key = { it.cacheKey }) { entry ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("排序 ${entry.order + 1}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        StatusPill(if (entry.enabled) "已启用" else "未启用")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val enabled = !state.deploymentInProgress && !state.cachedModDeletionInProgress
                        SmallAction(if (entry.enabled) "停用" else "启用", enabled) { actions.setModEnabled(entry.cacheKey, !entry.enabled) }
                        SmallAction("上移", enabled && entry.order > 0) { actions.moveMod(entry.cacheKey, -1) }
                        SmallAction("下移", enabled) { actions.moveMod(entry.cacheKey, 1) }
                        SmallAction("删除", enabled) { onShowDialog(DialogKind.DeleteCachedMod(entry.cacheKey)) }
                    }
                }
            }
        }
        state.gameModStorage?.mods?.takeIf { it.isNotEmpty() }?.let { mods ->
            item { DiagnosticPanel("查看游戏内内容", mods.joinToString("\n") { it.displayName ?: it.directoryName }) }
        }
    }
}

private enum class LibraryAction { Import, Launch, Sync, Refresh }
private data class LibraryPresentation(val summary: String, val actionLabel: String, val action: LibraryAction, val actionEnabled: Boolean = true)

private fun gameStorageMessage(storage: GameModStorageStatus?): LibraryPresentation = when {
    storage == null -> LibraryPresentation("正在检查游戏是否已准备好使用 Mod。", "重新检查", LibraryAction.Refresh)
    storage.isReady -> LibraryPresentation("游戏已准备好。同步后，启用的 Mod 会在下次启动游戏时生效。", "同步 Mod", LibraryAction.Sync)
    storage.failureCode == ModStorageFailureCode.GameRunning || storage.availability == ModStorageAvailability.GameRunning -> LibraryPresentation("请先退出游戏，再同步 Mod。", "同步 Mod", LibraryAction.Sync)
    storage.failureCode == ModStorageFailureCode.ExternalChangesDetected -> LibraryPresentation("发现游戏内的其他 Mod。同步前会由你确认是否替换。", "同步 Mod", LibraryAction.Sync)
    storage.availability == ModStorageAvailability.ProviderUnavailable -> LibraryPresentation("需要先启动一次游戏，以启用 Mod 服务。返回后重新检查即可继续同步。", "启动游戏以启用服务", LibraryAction.Launch)
    storage.availability in setOf(ModStorageAvailability.ProviderMissing, ModStorageAvailability.Unauthorized, ModStorageAvailability.Incompatible) -> LibraryPresentation("游戏还没有准备好使用 Mod。请先完成“准备游戏”。", "重新检查", LibraryAction.Refresh)
    else -> LibraryPresentation("暂时无法同步 Mod。请重新检查游戏状态。", "重新检查", LibraryAction.Refresh)
}

@Composable
private fun SettingsScreen(state: ManagerUiState, actions: ManagerActions, wide: Boolean, onShowDialog: (DialogKind) -> Unit) {
    ScreenList(wide) {
        item { HeroPanel("设置与帮助", "把数据和决定留在你手中", "Mod、下载和修补文件保存在应用私有目录。修补或同步游戏前，应用始终会要求你确认。") }
        item { SectionLabel("存储", "${state.cachedMods.size} 个 Mod") }
        item { ListPanel("清理本地 Mod 缓存", "只删除应用内已添加的 Mod，不会删除游戏或存档。", "管理") { onShowDialog(DialogKind.ClearCache) } }
        item { SectionLabel("帮助与安全", "随时可查看") }
        item { ListPanel("使用说明", "非官方工具、内容权利与兼容性风险。", "查看") { onShowDialog(DialogKind.Notice) } }
        item { ListPanel("隐私与数据", "本地保存范围、Steam 网络访问与会话。", "查看") { onShowDialog(DialogKind.Privacy) } }
        item { ListPanel("开源许可", "GNU GPLv3 与无担保说明。", "查看") { onShowDialog(DialogKind.License) } }
        item { DiagnosticPanel("应用诊断", "游戏：${gameProbeDiagnostic(state.gameProbeResult)}\n已添加 Mod：${state.cachedMods.size}\n下载任务：${state.downloadTasks.size}") }
        item { NoticeStrip("版本", "Manager 0.1.0") }
    }
}

@Composable
private fun DialogHost(state: ManagerUiState, actions: ManagerActions, dialog: DialogKind?, onDismiss: () -> Unit) {
    when (dialog) {
        DialogKind.Notice -> LegalNoticeDialog(actions.acceptNotice, onDismiss)
        DialogKind.Privacy -> TextDialog("隐私与数据流", "你选择导入的 Mod、下载暂存和修补工件保存在应用私有目录。浏览创意工坊时只会连接 Steam 公开服务和经过校验的下载地址。密码和 Steam Guard 验证码只用于认证；选择记住登录状态时，刷新令牌会由 Android Keystore 加密保存。", onDismiss)
        DialogKind.License -> TextDialog("开源许可与无担保", "本项目以 GNU GPLv3 发布，按“原样”提供且不提供担保。游戏、商标和 Mod 内容的权利归各自权利人所有。", onDismiss)
        DialogKind.ClearCache -> ConfirmDialog("清理本地 Mod 缓存？", "这会删除应用内已添加的 Mod，无法撤销；不会删除游戏、存档或下载任务。", "确认清理", { actions.clearModCache(); onDismiss() }, onDismiss)
        is DialogKind.DeleteCachedMod -> {
            val entry = state.deploymentPlan.firstOrNull { it.cacheKey == dialog.cacheKey }
            if (entry != null) ConfirmDialog("删除 ${entry.displayName}？", "这会删除应用内的 Mod 缓存，并从同步列表移除。游戏内已有 Mod 不会立即改变，之后同步时才会更新。", "删除 Mod", { actions.deleteCachedMod(entry.cacheKey); onDismiss() }, onDismiss)
        }
        DialogKind.SyncMods -> {
            val external = state.gameModStorage?.mods.orEmpty().filterNot { it.managedBySnapshot }
            ConfirmDialog(
                "同步 Mod 到游戏？",
                if (external.isEmpty()) "会将当前启用的 Mod 和顺序同步到游戏。请先完全退出游戏；同步完成后重新启动游戏。" else "发现 ${external.size} 个不由本应用管理的游戏内 Mod。继续会用当前列表替换它们；取消则不会修改游戏。",
                if (external.isEmpty()) "确认同步" else "替换并同步",
                { actions.syncMods(external.isNotEmpty()); onDismiss() },
                onDismiss,
            )
        }
        DialogKind.StopGameAndSync -> ConfirmDialog("关闭游戏后同步？", "游戏正在运行。继续会结束游戏进程，未保存的进度可能丢失；关闭后会重试同步。", "关闭并同步", { actions.confirmStopGameAndSync(); onDismiss() }, { actions.dismissStopGameAndSync(); onDismiss() })
        DialogKind.PatchCleanup -> {
            state.patchCleanup?.let { cleanup ->
                ConfirmDialog(
                    "清理临时文件？",
                    "这会删除应用内可安全清理的修补临时文件，包括提取的安装包、重签后的安装包和中断残留，释放 ${formatBytes(cleanup.sizeBytes)}。不会删除已安装游戏、存档、已导出的 APKS、Mod 或创意工坊下载内容。",
                    "确认清理",
                    { actions.confirmPatchCleanup(); onDismiss() },
                    { actions.dismissPatchCleanup(); onDismiss() },
                )
            }
        }
        is DialogKind.WorkshopTaskRemoval -> {
            val task = state.downloadTasks.firstOrNull { it.id == dialog.taskId }
            if (task != null) ConfirmDialog("删除下载任务？", "这会停止任务并删除应用内下载暂存。已添加的 Mod、游戏和存档不会受影响。", "删除任务", { actions.removeWorkshopDownload(task.id); onDismiss() }, onDismiss)
        }
        null -> Unit
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
                        Text("请在 Steam 中获取验证码并提交。不要重复提交同一验证码。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        LabeledTextField(guardCode, { guardCode = it }, "验证码")
                        PrimaryButton("提交验证码", guardCode.isNotBlank()) { actions.submitSteamGuard(guardCode); guardCode = "" }
                    }
                    is SteamAuthState.SteamAuthStatusUnknown, is SteamAuthState.AwaitingConfirmation -> {
                        Text("等待 Steam 确认", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("请在 Steam 完成确认后继续检查。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        PrimaryButton("继续检查", onClick = actions.checkPendingSteamLogin)
                    }
                    SteamAuthState.SigningIn, is SteamAuthState.VerifyingSteamGuard -> LoadingPanel("正在连接 Steam，请不要重复提交。")
                    else -> {
                        Text("登录 Steam", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("登录只用于受限下载内容。密码不会保存。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        LabeledTextField(username, { username = it }, "Steam 账号")
                        LabeledTextField(password, { password = it }, "Steam 密码", password = true)
                        ConfirmationCheckbox("记住登录状态，以便后台下载", rememberSession) { rememberSession = it }
                        PrimaryButton("登录", username.isNotBlank() && password.isNotBlank()) { actions.beginSteamLogin(username, password, rememberSession); password = "" }
                    }
                }
                if (!busy) SecondaryButton("关闭", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ScreenList(wide: Boolean, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (wide) 34.dp else 18.dp, vertical = if (wide) 10.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun HeroPanel(eyebrow: String, title: String, body: String, action: String? = null, actionEnabled: Boolean = true, onAction: (() -> Unit)? = null) {
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
private fun SectionLabel(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        StatusPill(trailing)
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
private fun ListPanel(title: String, body: String, trailing: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(17.dp), onClick = onClick) {
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
private fun NoticeStrip(title: String, body: String) {
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
private fun EmptyPanel(title: String, body: String) = NoticeStrip(title, body)

@Composable
private fun LoadingPanel(body: String, title: String = "正在处理") {
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
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
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
private fun SecondaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant), insideMargin = PaddingValues(horizontal = 15.dp, vertical = 11.dp), onClick = { if (enabled) onClick() }) {
        Text(label, Modifier.fillMaxWidth(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (enabled) MiuixTheme.colorScheme.primaryVariant else MiuixTheme.colorScheme.surfaceVariant).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp)) {
        Text(label, fontSize = 12.sp, color = if (enabled) MiuixTheme.colorScheme.onPrimaryVariant else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun LabeledTextField(
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
private fun ConfirmationCheckbox(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onToggle).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
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
        ApksExportUiState.Idle -> SecondaryButton("导出安装包（APKS）") { onExport(transactionId) }
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
                Text("这是独立社区工具，不隶属游戏发行方、Steam 或 Valve。请只处理你有权使用的游戏和 Mod。", fontSize = 14.sp)
                Text("添加 Mod 先保存在应用内；同步 Mod 和准备游戏会在真正改变游戏前明确要求你确认。", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                ConfirmationCheckbox("我已阅读并理解", checked) { checked = it }
                PrimaryButton("继续", checked) { onAccept(); onDismiss?.invoke() }
                onDismiss?.let { SecondaryButton("取消", onClick = it) }
            }
        }
    }
}

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
