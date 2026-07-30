package com.sultansgame.modmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.sultansgame.modmanager.model.PatchConfirmation
import com.sultansgame.modmanager.model.WorkshopAvailability
import com.sultansgame.modmanager.platform.game.GameProbeResult
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private enum class Destination(val titleRes: Int, val mark: String, val caption: String) {
    Mods(R.string.nav_mods, "01", "私有缓存"),
    Workshop(R.string.nav_workshop, "02", "公开工件"),
    Patch(R.string.nav_patch, "03", "安装迁移"),
    Settings(R.string.nav_settings, "04", "安全与关于"),
}

private sealed interface DialogKind {
    data object Notice : DialogKind
    data object Privacy : DialogKind
    data object License : DialogKind
    data object ClearCache : DialogKind
    data object SyncMods : DialogKind
    data object PatchWarning : DialogKind
    data class PatchCleanup(val transactionId: String) : DialogKind
}

class MainActivity : ComponentActivity() {
    private val viewModel by lazy { ViewModelProvider(this)[ManagerViewModel::class.java] }
    private val selectModZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importZip)
    }
    private val selectLocalApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLocalApk(it, displayNameFor(it)) }
    }
    private val selectLocalApkSet = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLocalApkSet(it, displayNameFor(it)) }
    }
    private val uninstallOriginalGame = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onGameUninstallResult()
    }
    private val configureUnknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshInstallPermission()
    }
    private val confirmPackageInstall = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private var pendingApksExportTransactionId: String? = null
    private val createApksDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        pendingApksExportTransactionId?.let { transactionId -> viewModel.writePreparedApks(transactionId, uri) }
        pendingApksExportTransactionId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvents.collect { event ->
                    when (event) {
                        is ManagerUiEvent.OpenGameUninstall -> uninstallOriginalGame.launch(
                            Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:com.gametree.sultan.pd"))
                                .putExtra(Intent.EXTRA_RETURN_RESULT, true),
                        )
                        is ManagerUiEvent.OpenUnknownSourcesSettings -> configureUnknownSources.launch(event.intent)
                        is ManagerUiEvent.ConfirmPackageInstall -> confirmPackageInstall.launch(event.intent)
                        is ManagerUiEvent.CreateApksExport -> {
                            pendingApksExportTransactionId = event.transactionId
                            createApksDocument.launch(event.suggestedName)
                        }
                    }
                }
            }
        }
        setContent {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ManagerApp(
                    state = state,
                    onImportMod = { selectModZip.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                    onImportLocalApk = { selectLocalApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                    onImportLocalApkSet = { selectLocalApkSet.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    onSelectInstalledGame = viewModel::selectInstalledGameSource,
                    onPreparePatch = viewModel::preparePatchArtifacts,
                    onRefreshPendingPatch = viewModel::refreshPendingPatchState,
                    onRequestOriginalUninstall = viewModel::requestOriginalGameUninstall,
                    onInstallPreparedArtifacts = viewModel::installPreparedArtifacts,
                    onExportPreparedApks = viewModel::exportPreparedApks,
                    onOpenUnknownSourcesSettings = viewModel::openUnknownSourcesSettings,
                    onRestartPatch = viewModel::restartPatchFlow,
                    onResumePreparedPatch = viewModel::resumePreparedPatch,
                    onLookupWorkshop = viewModel::lookupWorkshop,
                    onBeginSteamLogin = viewModel::beginSteamLogin,
                    onSubmitSteamGuard = viewModel::submitSteamGuard,
                    onLogoutSteam = viewModel::logoutSteam,
                    onBrowseWorkshop = viewModel::browseWorkshop,
                    onQueueWorkshopDownload = viewModel::queueWorkshopDownload,
                    onRetryWorkshopDownload = viewModel::retryWorkshopDownload,
                    onPauseWorkshopDownload = viewModel::pauseWorkshopDownload,
                    onResumeWorkshopDownload = viewModel::resumeWorkshopDownload,
                    onCancelWorkshopDownload = viewModel::cancelWorkshopDownload,
                    onConfirmWorkshopImport = viewModel::confirmWorkshopImport,
                    onDiscardWorkshopArtifact = viewModel::discardWorkshopArtifact,
                    onAcceptNotice = viewModel::acceptLegalNotice,
                    onClearModCache = viewModel::clearModCache,
                    onRefreshGameMods = viewModel::refreshGameModStorage,
                    onSetModEnabled = viewModel::setModEnabled,
                    onMoveMod = viewModel::moveMod,
                    onSyncMods = viewModel::syncMods,
                    onClearFeedback = viewModel::clearFeedback,
                    onUpdatePatchConfirmation = viewModel::updatePatchConfirmation,
                    onRequestPatchCleanup = viewModel::requestPatchCleanupConfirmation,
                    onConfirmPatchCleanup = viewModel::confirmPatchCleanup,
                    onDismissPatchCleanup = viewModel::dismissPatchCleanupConfirmation,
                )
            }
        }
    }

    private fun displayNameFor(uri: Uri): String = contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        cursor.takeIf { it.moveToFirst() }
            ?.getString(cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME))
    } ?: uri.lastPathSegment ?: "所选文件"
}

@Composable
private fun ManagerApp(
    state: ManagerUiState,
    onImportMod: () -> Unit,
    onImportLocalApk: () -> Unit,
    onImportLocalApkSet: () -> Unit,
    onSelectInstalledGame: () -> Unit,
    onPreparePatch: () -> Unit,
    onRefreshPendingPatch: () -> Unit,
    onRequestOriginalUninstall: (String) -> Unit,
    onInstallPreparedArtifacts: (String) -> Unit,
    onExportPreparedApks: (String) -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit,
    onRestartPatch: () -> Unit,
    onResumePreparedPatch: (String) -> Unit,
    onLookupWorkshop: (String) -> Unit,
    onBeginSteamLogin: (String, String) -> Unit,
    onSubmitSteamGuard: (String) -> Unit,
    onLogoutSteam: () -> Unit,
    onBrowseWorkshop: (com.sultansgame.modmanager.model.WorkshopBrowseQuery) -> Unit,
    onQueueWorkshopDownload: (com.sultansgame.modmanager.model.WorkshopItem) -> Unit,
    onRetryWorkshopDownload: (String) -> Unit,
    onPauseWorkshopDownload: (String) -> Unit,
    onResumeWorkshopDownload: (String) -> Unit,
    onCancelWorkshopDownload: (String) -> Unit,
    onConfirmWorkshopImport: (String) -> Unit,
    onDiscardWorkshopArtifact: (String) -> Unit,
    onAcceptNotice: () -> Unit,
    onClearModCache: () -> Unit,
    onRefreshGameMods: () -> Unit,
    onSetModEnabled: (String, Boolean) -> Unit,
    onMoveMod: (String, Int) -> Unit,
    onSyncMods: (Boolean) -> Unit,
    onClearFeedback: () -> Unit,
    onUpdatePatchConfirmation: (PatchConfirmation) -> Unit,
    onRequestPatchCleanup: (String) -> Unit,
    onConfirmPatchCleanup: (String) -> Unit,
    onDismissPatchCleanup: () -> Unit,
) {
    var destinationIndex by remember {
        mutableIntStateOf(
            Destination.Patch.ordinal,
        )
    }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    LaunchedEffect(state.patchCleanupConfirmation?.transactionId) {
        state.patchCleanupConfirmation?.let { cleanup -> dialog = DialogKind.PatchCleanup(cleanup.transactionId) }
    }
    val destination = Destination.entries[destinationIndex]

    BoxWithConstraints(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        val wideLayout = maxWidth >= 700.dp
        if (wideLayout) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(destinationIndex, state.cachedMods.size) { destinationIndex = it }
                ContentArea(
                    modifier = Modifier.weight(1f),
                    destination = destination,
                    state = state,
                    wideLayout = true,
                    onImportMod = onImportMod,
                    onLookupWorkshop = onLookupWorkshop,
                    onBeginSteamLogin = onBeginSteamLogin,
                    onSubmitSteamGuard = onSubmitSteamGuard,
                    onLogoutSteam = onLogoutSteam,
                    onBrowseWorkshop = onBrowseWorkshop,
                    onQueueWorkshopDownload = onQueueWorkshopDownload,
                    onRetryWorkshopDownload = onRetryWorkshopDownload,
                    onPauseWorkshopDownload = onPauseWorkshopDownload,
                    onResumeWorkshopDownload = onResumeWorkshopDownload,
                    onCancelWorkshopDownload = onCancelWorkshopDownload,
                    onConfirmWorkshopImport = onConfirmWorkshopImport,
                    onDiscardWorkshopArtifact = onDiscardWorkshopArtifact,
                    onRefreshGameMods = onRefreshGameMods,
                    onSetModEnabled = onSetModEnabled,
                    onMoveMod = onMoveMod,
                    onSyncMods = onSyncMods,
                    onShowDialog = { dialog = it },
                    onImportLocalApk = onImportLocalApk,
                    onImportLocalApkSet = onImportLocalApkSet,
                    onSelectInstalledGame = onSelectInstalledGame,
                    onPreparePatch = onPreparePatch,
                    onRefreshPendingPatch = onRefreshPendingPatch,
                    onRequestOriginalUninstall = onRequestOriginalUninstall,
                    onInstallPreparedArtifacts = onInstallPreparedArtifacts,
                    onExportPreparedApks = onExportPreparedApks,
                    onOpenUnknownSourcesSettings = onOpenUnknownSourcesSettings,
                    onRestartPatch = onRestartPatch,
                    onResumePreparedPatch = onResumePreparedPatch,
                    onUpdatePatchConfirmation = onUpdatePatchConfirmation,
                    onRequestPatchCleanup = { onRequestPatchCleanup(it); dialog = DialogKind.PatchCleanup(it) },
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                CompactHeader(destination)
                ContentArea(
                    modifier = Modifier.weight(1f),
                    destination = destination,
                    state = state,
                    wideLayout = false,
                    onImportMod = onImportMod,
                    onLookupWorkshop = onLookupWorkshop,
                    onBeginSteamLogin = onBeginSteamLogin,
                    onSubmitSteamGuard = onSubmitSteamGuard,
                    onLogoutSteam = onLogoutSteam,
                    onBrowseWorkshop = onBrowseWorkshop,
                    onQueueWorkshopDownload = onQueueWorkshopDownload,
                    onRetryWorkshopDownload = onRetryWorkshopDownload,
                    onPauseWorkshopDownload = onPauseWorkshopDownload,
                    onResumeWorkshopDownload = onResumeWorkshopDownload,
                    onCancelWorkshopDownload = onCancelWorkshopDownload,
                    onConfirmWorkshopImport = onConfirmWorkshopImport,
                    onDiscardWorkshopArtifact = onDiscardWorkshopArtifact,
                    onRefreshGameMods = onRefreshGameMods,
                    onSetModEnabled = onSetModEnabled,
                    onMoveMod = onMoveMod,
                    onSyncMods = onSyncMods,
                    onShowDialog = { dialog = it },
                    onImportLocalApk = onImportLocalApk,
                    onImportLocalApkSet = onImportLocalApkSet,
                    onSelectInstalledGame = onSelectInstalledGame,
                    onPreparePatch = onPreparePatch,
                    onRefreshPendingPatch = onRefreshPendingPatch,
                    onRequestOriginalUninstall = onRequestOriginalUninstall,
                    onInstallPreparedArtifacts = onInstallPreparedArtifacts,
                    onExportPreparedApks = onExportPreparedApks,
                    onOpenUnknownSourcesSettings = onOpenUnknownSourcesSettings,
                    onRestartPatch = onRestartPatch,
                    onResumePreparedPatch = onResumePreparedPatch,
                    onUpdatePatchConfirmation = onUpdatePatchConfirmation,
                    onRequestPatchCleanup = { onRequestPatchCleanup(it); dialog = DialogKind.PatchCleanup(it) },
                )
                CompactNavigation(destinationIndex) { destinationIndex = it }
            }
        }
        state.feedback?.let { FeedbackBanner(it, onClearFeedback, wideLayout) }
    }

    if (state.noticeAccepted == null) PreparingNoticeDialog()
    else if (state.noticeAccepted == false) LegalNoticeDialog(onAcceptNotice)


    when (val activeDialog = dialog) {
        DialogKind.Notice -> LegalNoticeDialog(onAcceptNotice) { dialog = null }
        DialogKind.Privacy -> TextDialog(stringResource(R.string.privacy_title), stringResource(R.string.privacy_body)) { dialog = null }
        DialogKind.License -> TextDialog(stringResource(R.string.license_title), stringResource(R.string.license_body)) { dialog = null }
        DialogKind.ClearCache -> ConfirmDialog(
            title = stringResource(R.string.clear_cache_title),
            body = stringResource(R.string.clear_cache_body),
            confirmLabel = stringResource(R.string.clear_cache_confirm),
            onConfirm = { onClearModCache(); dialog = null },
            onDismiss = { dialog = null },
        )
        DialogKind.PatchWarning -> TextDialog(
            title = "安装补丁前的重要说明",
            body = "这是一项非官方修改，可能需要 Android 系统安装确认，并可能因游戏版本、签名或系统限制而失败。请在继续前自行确认存档与兼容性风险。\n\n给小米/MIUI/澎湃系统用户的说明：由于这些系统对 Android API 的改动，可能会产生无法预知的情况导致安装失败。如果遇到该问题，您大概率可以通过在系统设置的开发者选项中关闭 MIUI 优化/系统优化来修复。应用不会自动修改任何系统设置。",
            onDismiss = { dialog = null },
        )
        DialogKind.SyncMods -> {
            val externalMods = state.gameModStorage?.mods.orEmpty().filterNot { it.managedBySnapshot }
            ConfirmDialog(
                title = "同步启用的 Mod 到游戏？",
                body = if (externalMods.isEmpty()) {
                    "同步会以当前启用列表与顺序替换游戏端受 Manager 管理的 Mod。请先完全退出游戏；同步成功后需要冷启动游戏。"
                } else {
                    "游戏目录发现 ${externalMods.size} 个外部 Mod。继续会用当前 Manager 快照替换它们；取消则不修改游戏目录。请先完全退出游戏。"
                },
                confirmLabel = if (externalMods.isEmpty()) "确认同步" else "覆盖外部 Mod 并同步",
                onConfirm = { onSyncMods(externalMods.isNotEmpty()); dialog = null },
                onDismiss = { dialog = null },
            )
        }
        is DialogKind.PatchCleanup -> {
            val candidate = state.patchCleanup?.takeIf { it.transactionId == activeDialog.transactionId }
            if (candidate == null) {
                LaunchedEffect(activeDialog.transactionId) { dialog = null; onDismissPatchCleanup() }
            } else {
                ConfirmDialog(
                    title = "删除已准备的修补 APK？",
                    body = "将删除 Manager 私有目录内本次修补的输入、重签 APK、loader 模板和事务记录，释放约 ${formatBytes(candidate.sizeBytes)}。删除后无法继续本次安装或再次导出，需要重新选择来源。不会删除已导出的 APKS 文件、已安装游戏或存档。",
                    confirmLabel = "删除私有修补文件",
                    onConfirm = { onConfirmPatchCleanup(candidate.transactionId); dialog = null },
                    onDismiss = { onDismissPatchCleanup(); dialog = null },
                )
            }
        }
        null -> Unit
    }
}

@Composable
private fun Sidebar(selectedIndex: Int, modCount: Int, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxHeight().width(254.dp).background(MiuixTheme.colorScheme.surfaceContainer)
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
        Spacer(Modifier.height(32.dp))
        Text("控制台", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(10.dp))
        Destination.entries.forEachIndexed { index, item ->
            SidebarDestination(item, selectedIndex == index) { onSelect(index) }
            Spacer(Modifier.height(5.dp))
        }
        Spacer(Modifier.weight(1f))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant), insideMargin = PaddingValues(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("本地工作区", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("$modCount 个 Mod 已缓存", fontSize = 13.sp)
                Text("不会写入游戏目录", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun SidebarDestination(destination: Destination, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) MiuixTheme.colorScheme.primaryVariant else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(destination.mark, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (selected) MiuixTheme.colorScheme.onPrimaryVariant else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(destination.titleRes), fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(destination.caption, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun CompactHeader(destination: Destination) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(32.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(R.string.app_name), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(destination.titleRes), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MiuixTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) { Text("S", fontSize = if (size > 40.dp) 22.sp else 16.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onPrimary) }
}

@Composable
private fun CompactNavigation(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Destination.entries.forEachIndexed { index, destination ->
            val selected = selectedIndex == index
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (selected) MiuixTheme.colorScheme.primaryVariant else Color.Transparent)
                    .clickable { onSelect(index) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(destination.mark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(destination.titleRes), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ContentArea(
    modifier: Modifier,
    destination: Destination,
    state: ManagerUiState,
    wideLayout: Boolean,
    onImportMod: () -> Unit,
    onLookupWorkshop: (String) -> Unit,
    onBeginSteamLogin: (String, String) -> Unit,
    onSubmitSteamGuard: (String) -> Unit,
    onLogoutSteam: () -> Unit,
    onBrowseWorkshop: (com.sultansgame.modmanager.model.WorkshopBrowseQuery) -> Unit,
    onQueueWorkshopDownload: (com.sultansgame.modmanager.model.WorkshopItem) -> Unit,
    onRetryWorkshopDownload: (String) -> Unit,
    onPauseWorkshopDownload: (String) -> Unit,
    onResumeWorkshopDownload: (String) -> Unit,
    onCancelWorkshopDownload: (String) -> Unit,
    onConfirmWorkshopImport: (String) -> Unit,
    onDiscardWorkshopArtifact: (String) -> Unit,
    onRefreshGameMods: () -> Unit,
    onSetModEnabled: (String, Boolean) -> Unit,
    onMoveMod: (String, Int) -> Unit,
    onSyncMods: (Boolean) -> Unit,
    onShowDialog: (DialogKind) -> Unit,
    onImportLocalApk: () -> Unit,
    onImportLocalApkSet: () -> Unit,
    onSelectInstalledGame: () -> Unit,
    onPreparePatch: () -> Unit,
    onRefreshPendingPatch: () -> Unit,
    onRequestOriginalUninstall: (String) -> Unit,
    onInstallPreparedArtifacts: (String) -> Unit,
    onExportPreparedApks: (String) -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit,
    onRestartPatch: () -> Unit,
    onResumePreparedPatch: (String) -> Unit,
    onUpdatePatchConfirmation: (PatchConfirmation) -> Unit,
    onRequestPatchCleanup: (String) -> Unit,
) {
    Column(modifier) {
        if (wideLayout) ContentHeader(destination)
        when (destination) {
            Destination.Mods -> ModsScreen(
                state = state,
                wide = wideLayout,
                onImport = onImportMod,
                onRefreshGame = onRefreshGameMods,
                onSetEnabled = onSetModEnabled,
                onMove = onMoveMod,
                onSync = { onShowDialog(DialogKind.SyncMods) },
            )
            Destination.Workshop -> WorkshopNavigation(
                state = state,
                wide = wideLayout,
                onLookup = onLookupWorkshop,
                onBeginSteamLogin = onBeginSteamLogin,
                onSubmitSteamGuard = onSubmitSteamGuard,
                onLogoutSteam = onLogoutSteam,
                onBrowse = onBrowseWorkshop,
                onQueueDownload = onQueueWorkshopDownload,
                onRetryDownload = onRetryWorkshopDownload,
                onPauseDownload = onPauseWorkshopDownload,
                onResumeDownload = onResumeWorkshopDownload,
                onCancelDownload = onCancelWorkshopDownload,
                onConfirmImport = onConfirmWorkshopImport,
                onDiscardArtifact = onDiscardWorkshopArtifact,
            )
            Destination.Patch -> PatchScreen(
                wide = wideLayout,
                state = state,
                onShowWarning = { onShowDialog(DialogKind.PatchWarning) },
                onImportLocalApk = onImportLocalApk,
                onImportLocalApkSet = onImportLocalApkSet,
                onSelectInstalledGame = onSelectInstalledGame,
                onPrepare = onPreparePatch,
                onRefreshPending = onRefreshPendingPatch,
                onRequestUninstall = onRequestOriginalUninstall,
                onInstallPrepared = onInstallPreparedArtifacts,
                onExportPreparedApks = onExportPreparedApks,
                onOpenUnknownSourcesSettings = onOpenUnknownSourcesSettings,
                onRestart = onRestartPatch,
                onResumePreparedPatch = onResumePreparedPatch,
                onUpdateConfirmation = onUpdatePatchConfirmation,
                onRequestPatchCleanup = onRequestPatchCleanup,
            )
            Destination.Settings -> SettingsScreen(state, wideLayout, onShowDialog)
        }
    }
}

@Composable
private fun ContentHeader(destination: Destination) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(destination.titleRes), fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(destination.caption, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        StatusPill("0.1.0")
    }
}

@Composable
private fun ModsScreen(
    state: ManagerUiState,
    wide: Boolean,
    onImport: () -> Unit,
    onRefreshGame: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onSync: () -> Unit,
) {
    val storage = state.gameModStorage
    ScreenList(wide) {
        item {
            HeroPanel(
                eyebrow = "本地导入与部署",
                title = "管理游戏 Mod 快照",
                body = "ZIP 与 Workshop 内容先在 Manager 私有目录校验。启用并排序后，使用游戏内服务原子同步；查看目录不会启动游戏。",
                action = stringResource(R.string.action_import_zip),
                onAction = onImport,
            )
        }
        item {
            if (storage == null) LoadingPanel("正在读取游戏 Mod 目录…")
            else NoticeStrip(
                "游戏 Mod 目录",
                "${storage.availability} · ${storage.mods.size} 个目录${storage.reason?.let { "\n$it" }.orEmpty()}",
            )
        }
        item { PrimaryButton("刷新游戏 Mod 目录", !state.deploymentInProgress, onRefreshGame) }
        item { SectionLabel("部署计划", "${state.deploymentPlan.count { it.enabled }} 个启用") }
        if (state.deploymentPlan.isEmpty()) item {
            EmptyPanel("尚无缓存内容", "导入第一个 ZIP Mod 后，可在此启用、调整顺序并同步。")
        } else items(state.deploymentPlan, key = { it.cacheKey }) { entry ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("顺序 ${entry.order} · ${entry.contentDigestSha256.take(12)}…", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        StatusPill(if (entry.enabled) "已启用" else "未启用")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallAction("${if (entry.enabled) "停用" else "启用"}") { onSetEnabled(entry.cacheKey, !entry.enabled) }
                        SmallAction("上移", entry.order > 0) { onMove(entry.cacheKey, -1) }
                        SmallAction("下移") { onMove(entry.cacheKey, 1) }
                    }
                }
            }
        }
        item {
            PrimaryButton(
                label = if (state.deploymentInProgress) "正在同步…" else "同步启用的 Mod 到游戏",
                enabled = !state.deploymentInProgress,
                onClick = onSync,
            )
        }
        if (storage?.mods?.isNotEmpty() == true) {
            item { SectionLabel("游戏目录实际内容", "${storage.mods.size} 个") }
            items(storage.mods, key = { it.directoryName }) { mod ->
                ListPanel(mod.directoryName, if (mod.managedBySnapshot) "由 Manager 快照管理" else "外部发现：同步前会要求确认处理", if (mod.managedBySnapshot) "已管理" else "外部")
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (enabled) MiuixTheme.colorScheme.primaryVariant else MiuixTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 12.sp, color = if (enabled) MiuixTheme.colorScheme.onPrimaryVariant else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}


@Composable
private fun WorkshopNavigation(
    state: ManagerUiState,
    wide: Boolean,
    onLookup: (String) -> Unit,
    onBeginSteamLogin: (String, String) -> Unit,
    onSubmitSteamGuard: (String) -> Unit,
    onLogoutSteam: () -> Unit,
    onBrowse: (com.sultansgame.modmanager.model.WorkshopBrowseQuery) -> Unit,
    onQueueDownload: (com.sultansgame.modmanager.model.WorkshopItem) -> Unit,
    onRetryDownload: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onConfirmImport: (String) -> Unit,
    onDiscardArtifact: (String) -> Unit,
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "browse") {
        composable("browse") {
            WorkshopScreen(
                state = state,
                wide = wide,
                onLookup = { id ->
                    onLookup(id)
                    navController.navigate("detail/$id")
                },
                onBeginSteamLogin = onBeginSteamLogin,
                onSubmitSteamGuard = onSubmitSteamGuard,
                onLogoutSteam = onLogoutSteam,
                onBrowse = onBrowse,
                onOpenQueue = { navController.navigate("queue") },
                onQueueDownload = onQueueDownload,
                onRetryDownload = onRetryDownload,
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
                onCancelDownload = onCancelDownload,
                onConfirmImport = onConfirmImport,
                onDiscardArtifact = onDiscardArtifact,
            )
        }
        composable(
            route = "detail/{publishedFileId}",
            arguments = listOf(navArgument("publishedFileId") { type = NavType.StringType }),
        ) { entry ->
            val routeId = entry.arguments?.getString("publishedFileId")
            val detail = state.workshop as? WorkshopUiState.Item
            LaunchedEffect(routeId) {
                if (!routeId.isNullOrBlank() && detail?.item?.publishedFileId.toString() != routeId) {
                    onLookup(routeId)
                }
            }
            if (routeId.isNullOrBlank()) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else when {
                detail != null && detail.item.publishedFileId.toString() == routeId -> WorkshopDetailScreen(
                    item = detail.item,
                    wide = wide,
                    onBack = {
                        onLookup("")
                        navController.popBackStack()
                    },
                    onQueueDownload = onQueueDownload,
                )
                state.workshop is WorkshopUiState.Error -> ScreenList(wide) {
                    item { ErrorPanel((state.workshop).reason) }
                    item { PrimaryButton("返回创意工坊") { navController.popBackStack() } }
                }
                else -> ScreenList(wide) { item { LoadingPanel("正在读取 Workshop 详情…") } }
            }
        }
        composable("queue") {
            WorkshopQueueScreen(
                state = state,
                wide = wide,
                onRetryDownload = onRetryDownload,
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
                onCancelDownload = onCancelDownload,
                onConfirmImport = onConfirmImport,
                onDiscardArtifact = onDiscardArtifact,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
@Composable
private fun WorkshopQueueScreen(
    state: ManagerUiState,
    wide: Boolean,
    onRetryDownload: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onConfirmImport: (String) -> Unit,
    onDiscardArtifact: (String) -> Unit,
    onBack: () -> Unit,
) {
    ScreenList(wide) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("下载中心", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("下载仅位于 Manager 私有暂存区；完成后仍需要确认并校验才会进入私有 Mod 缓存。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text("← 返回创意工坊", modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp), fontSize = 13.sp)
                }
            }
        }
        if (state.downloadTasks.isEmpty()) {
            item { EmptyPanel("下载队列为空", "从 Workshop 条目详情选择“加入下载队列”后，任务将显示在此处。") }
        } else {
            items(state.downloadTasks, key = { it.id }) { task ->
                WorkshopDownloadTaskCard(task, onRetryDownload, onPauseDownload, onResumeDownload, onCancelDownload, onConfirmImport, onDiscardArtifact)
            }
        }
    }
}

@Composable
private fun WorkshopDownloadTaskCard(
    task: com.sultansgame.modmanager.model.DownloadTask,
    onRetryDownload: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onConfirmImport: (String) -> Unit,
    onDiscardArtifact: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.title.ifBlank { "Workshop ${task.publishedFileId}" }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("${task.stage} · ${formatBytes(task.downloadedBytes)}${task.totalBytes?.let { " / ${formatBytes(it)}" } ?: ""}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            when (task.stage) {
                com.sultansgame.modmanager.model.DownloadStage.AwaitingImportConfirmation -> {
                    PrimaryButton("检查并导入 Mod") { onConfirmImport(task.id) }
                    PrimaryButton("丢弃下载内容") { onDiscardArtifact(task.id) }
                }
                com.sultansgame.modmanager.model.DownloadStage.Paused -> {
                    PrimaryButton("继续下载") { onResumeDownload(task.id) }
                    PrimaryButton("取消下载") { onCancelDownload(task.id) }
                }
                com.sultansgame.modmanager.model.DownloadStage.Failed,
                com.sultansgame.modmanager.model.DownloadStage.NeedsLogin -> {
                    PrimaryButton("重试") { onRetryDownload(task.id) }
                    PrimaryButton("取消下载") { onCancelDownload(task.id) }
                }
                com.sultansgame.modmanager.model.DownloadStage.Imported,
                com.sultansgame.modmanager.model.DownloadStage.Cancelled -> Unit
                else -> {
                    PrimaryButton("暂停下载") { onPauseDownload(task.id) }
                    PrimaryButton("取消下载") { onCancelDownload(task.id) }
                }
            }
        }
    }
}

@Composable
private fun WorkshopDetailScreen(
    item: com.sultansgame.modmanager.model.WorkshopItem,
    wide: Boolean,
    onBack: () -> Unit,
    onQueueDownload: (com.sultansgame.modmanager.model.WorkshopItem) -> Unit,
) {
    ScreenList(wide) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("← 返回创意工坊", modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp), fontSize = 13.sp)
                    Text("WORKSHOP ITEM · ${item.publishedFileId}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(item.title, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text(item.authorName.ifBlank { "未知作者" }, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    item.shortDescription.takeIf(String::isNotBlank)?.let { Text(it, fontSize = 15.sp) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("说明", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        item.description.ifBlank { "Steam 未提供该条目的详细说明。" },
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        listOfNotNull(
                            item.declaredSizeBytes?.let(::formatBytes),
                            item.createdAtEpochSeconds?.let { "已创建" },
                            item.updatedAtEpochSeconds?.let { "已更新" },
                            item.subscriptions?.let { "$it 订阅" },
                        ).joinToString(" · ").ifBlank { "Steam 创意工坊条目" },
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        if (item.tags.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("标签", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(item.tags.joinToString(" · "), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        item {
            if (item.canDownload) {
                PrimaryButton("加入下载队列") { onQueueDownload(item) }
            } else {
                NoticeStrip("当前不可下载", "Steam 没有为此条目提供可验证的公开下载信息。")
            }
        }
    }
}

@Composable
private fun WorkshopScreen(
    state: ManagerUiState,
    wide: Boolean,
    onLookup: (String) -> Unit,
    onBeginSteamLogin: (String, String) -> Unit,
    onSubmitSteamGuard: (String) -> Unit,
    onLogoutSteam: () -> Unit,
    onBrowse: (com.sultansgame.modmanager.model.WorkshopBrowseQuery) -> Unit,
    onOpenQueue: () -> Unit,
    onQueueDownload: (com.sultansgame.modmanager.model.WorkshopItem) -> Unit,
    onRetryDownload: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onConfirmImport: (String) -> Unit,
    onDiscardArtifact: (String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var guardCode by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf(state.workshopBrowse.query.searchText) }
    var publishedFileId by rememberSaveable { mutableStateOf("") }
    var showSteamLogin by rememberSaveable { mutableStateOf(false) }
    var showAdvancedFilters by rememberSaveable { mutableStateOf(false) }
    var filterDraft by remember { mutableStateOf(state.workshopBrowse.query) }
    val signedIn = state.steamAuthState as? com.sultansgame.modmanager.model.SteamAuthState.SignedIn
    LaunchedEffect(state.workshopBrowse.query) { filterDraft = state.workshopBrowse.query }
    LaunchedEffect(state.workshopBrowse.items.isEmpty(), state.workshopBrowse.error, state.workshopBrowse.isLoading) {
        if (
            state.workshopBrowse.items.isEmpty() &&
            state.workshopBrowse.error == null &&
            !state.workshopBrowse.isLoading
        ) {
            onBrowse(com.sultansgame.modmanager.model.WorkshopBrowseQuery())
        }
    }

    ScreenList(wide) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("STEAM WORKSHOP · APPID 3117820", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("苏丹的游戏创意工坊", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("匿名浏览公开 Mod；下载先进入私有暂存区，确认后才校验并导入，不会直接写入游戏目录。", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            signedIn?.accountName ?: "匿名浏览",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        SmallAction(if (signedIn == null) "登录 Steam" else "退出 Steam") {
                            if (signedIn == null) {
                                showSteamLogin = true
                            } else {
                                onLogoutSteam()
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("探索创意工坊", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("无需登录即可浏览公开 Mod；登录仅用于账号受限内容。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    WorkshopTextField(query, { query = it }, "关键词")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallAction("热门") { onBrowse(com.sultansgame.modmanager.model.WorkshopBrowseQuery(searchText = query)) }
                        SmallAction("最新") { onBrowse(com.sultansgame.modmanager.model.WorkshopBrowseQuery(searchText = query, sortKey = com.sultansgame.modmanager.model.WorkshopBrowseQuery.SORT_MOST_RECENT)) }
                        SmallAction("筛选") { showAdvancedFilters = true }
                        SmallAction("下载中心") { onOpenQueue() }
                    }
                }
            }
        }
        when (val browse = state.workshopBrowse) {
            else -> {
                if (browse.isLoading) item { LoadingPanel("正在浏览 Steam 创意工坊…") }
                browse.error?.let { reason -> item { ErrorPanel(reason) } }
                if (browse.items.isNotEmpty()) {
                    item { SectionLabel("公开 Mod", "${browse.totalCount} 项") }
                    items(browse.items, key = { it.publishedFileId.toString() }) { item ->
                        ListPanel(
                            item.title,
                            listOfNotNull(
                                item.authorName.takeIf(String::isNotBlank),
                                item.declaredSizeBytes?.let(::formatBytes),
                                item.tags.take(2).takeIf { it.isNotEmpty() }?.joinToString(),
                            ).joinToString(" · ").ifBlank { "Steam 创意工坊条目" },
                            if (item.canDownload) "查看详情" else "不可下载",
                        ) { onLookup(item.publishedFileId.toString()) }
                    }
                    if (browse.hasMore) item {
                        PrimaryButton("加载更多") {
                            onBrowse(browse.query.copy(page = browse.query.page + 1))
                        }
                    }
                } else if (!browse.isLoading && browse.error == null) {
                    item { PrimaryButton("浏览热门 Mod") { onBrowse(com.sultansgame.modmanager.model.WorkshopBrowseQuery()) } }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("按 PublishedFileId 查询", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    WorkshopTextField(publishedFileId, { publishedFileId = it.filter(Char::isDigit) }, "PublishedFileId", numeric = true)
                    PrimaryButton("查询详情", publishedFileId.isNotEmpty()) { onLookup(publishedFileId) }
                }
            }
        }
        when (val workshop = state.workshop) {
            WorkshopUiState.Idle -> Unit
            WorkshopUiState.Loading -> item { LoadingPanel("正在读取 Workshop 详情…") }
            is WorkshopUiState.Item -> Unit
            is WorkshopUiState.Error -> item { ErrorPanel(workshop.reason) }
        }
        if (state.downloadTasks.isNotEmpty()) {
            item { SectionLabel("下载队列", "${state.downloadTasks.size} 项") }
            items(state.downloadTasks, key = { it.id }) { task ->
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(task.title.ifBlank { "Workshop ${task.publishedFileId}" }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("${task.stage} · ${formatBytes(task.downloadedBytes)}${task.totalBytes?.let { " / ${formatBytes(it)}" } ?: ""}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (task.stage == com.sultansgame.modmanager.model.DownloadStage.AwaitingImportConfirmation) {
                            Text("已下载 ${task.completedFileCount} 个文件，SHA-256 ${task.rawArtifactDigestSha256?.take(12) ?: "未知"}…", fontSize = 12.sp)
                            PrimaryButton("检查并导入 Mod") { onConfirmImport(task.id) }
                            PrimaryButton("丢弃下载内容") { onDiscardArtifact(task.id) }
                        } else if (task.stage == com.sultansgame.modmanager.model.DownloadStage.Paused) {
                            PrimaryButton("继续下载") { onResumeDownload(task.id) }
                        } else if (task.stage == com.sultansgame.modmanager.model.DownloadStage.Failed || task.stage == com.sultansgame.modmanager.model.DownloadStage.NeedsLogin) {
                            PrimaryButton("重试") { onRetryDownload(task.id) }
                        } else if (task.stage !in setOf(com.sultansgame.modmanager.model.DownloadStage.Imported, com.sultansgame.modmanager.model.DownloadStage.Cancelled)) {
                            PrimaryButton("暂停下载") { onPauseDownload(task.id) }
                            PrimaryButton("取消下载") { onCancelDownload(task.id) }
                        }
                    }
                }
            }
        }
    }

    if (showSteamLogin) {
        Dialog(onDismissRequest = { if (state.steamAuthState !is com.sultansgame.modmanager.model.SteamAuthState.SigningIn) showSteamLogin = false }) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (val auth = state.steamAuthState) {
                        is com.sultansgame.modmanager.model.SteamAuthState.SignedIn -> {
                            Text("已登录 Steam", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("${auth.accountName} · ${auth.steamId}", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            PrimaryButton("退出 Steam") { onLogoutSteam(); showSteamLogin = false }
                        }
                        is com.sultansgame.modmanager.model.SteamAuthState.SteamGuardRequired -> {
                            Text("需要 ${auth.challenge}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("输入验证码后可重试；若 Steam 要求在 App 中批准登录，请改用可输入的 Guard 或邮箱验证码。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            WorkshopTextField(guardCode, { guardCode = it }, "验证码")
                            PrimaryButton("提交验证码", guardCode.isNotBlank()) { onSubmitSteamGuard(guardCode); guardCode = "" }
                        }
                        com.sultansgame.modmanager.model.SteamAuthState.SigningIn -> LoadingPanel("正在连接 Steam…")
                        else -> {
                            Text("登录 Steam", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("账号密码仅用于本次认证；刷新令牌由 Android Keystore 加密保存。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            WorkshopTextField(username, { username = it }, "Steam 账号")
                            WorkshopTextField(password, { password = it }, "Steam 密码", password = true)
                            PrimaryButton("登录 Steam", username.isNotBlank() && password.isNotEmpty()) {
                                onBeginSteamLogin(username, password)
                                password = ""
                            }
                        }
                    }
                    if (state.steamAuthState !is com.sultansgame.modmanager.model.SteamAuthState.SigningIn) {
                        Text("关闭", modifier = Modifier.fillMaxWidth().clickable { showSteamLogin = false }.padding(8.dp), fontSize = 13.sp)
                    }
                }
            }
        }
    }
    if (showAdvancedFilters) {
        Dialog(onDismissRequest = { showAdvancedFilters = false }) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val currentSort = state.workshopBrowse.sortOptions
                        .firstOrNull { it.key == filterDraft.sortKey }
                    Text("高级筛选", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("筛选项由 Steam Community 页面动态提供；应用后会回到第一页。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)

                    if (state.workshopBrowse.sectionOptions.isNotEmpty()) {
                        Text("分类", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        state.workshopBrowse.sectionOptions.forEach { section ->
                            val requiresAccount = section.key == com.sultansgame.modmanager.model.WorkshopBrowseQuery.SECTION_MY_SUBSCRIPTIONS && signedIn == null
                            SmallAction(
                                if (filterDraft.sectionKey == section.key) "✓ ${section.label}" else section.label,
                                enabled = !requiresAccount,
                            ) {
                                filterDraft = filterDraft.copy(sectionKey = section.key)
                            }
                            if (requiresAccount) Text("${section.label} 需要登录 Steam。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }

                    if (state.workshopBrowse.sortOptions.isNotEmpty()) {
                        Text("排序", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        state.workshopBrowse.sortOptions.forEach { option ->
                            SmallAction(if (filterDraft.sortKey == option.key) "✓ ${option.label}" else option.label) {
                                filterDraft = filterDraft.copy(sortKey = option.key)
                            }
                        }
                    }
                    if (currentSort?.supportsPeriod == true && state.workshopBrowse.periodOptions.isNotEmpty()) {
                        Text("热门时间范围", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        state.workshopBrowse.periodOptions.forEach { option ->
                            SmallAction(if (filterDraft.periodDays == option.days) "✓ ${option.label}" else option.label) {
                                filterDraft = filterDraft.copy(periodDays = option.days)
                            }
                        }
                    }
                    if (state.workshopBrowse.supportsIncompatibleFilter) {
                        SmallAction(if (filterDraft.showIncompatible) "✓ 显示不兼容项" else "显示不兼容项") {
                            filterDraft = filterDraft.copy(showIncompatible = !filterDraft.showIncompatible)
                        }
                    }

                    Text("每页条目数", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    com.sultansgame.modmanager.model.WorkshopBrowseQuery.PAGE_SIZE_OPTIONS.forEach { pageSize ->
                        SmallAction(if (filterDraft.pageSize == pageSize) "✓ $pageSize" else "$pageSize") {
                            filterDraft = filterDraft.copy(pageSize = pageSize)
                        }
                    }

                    DateRangeFilterEditor(
                        title = "创建时间（Unix 秒）",
                        range = filterDraft.createdDateRange,
                        onChange = { filterDraft = filterDraft.copy(createdDateRange = it) },
                    )
                    DateRangeFilterEditor(
                        title = "更新时间（Unix 秒）",
                        range = filterDraft.updatedDateRange,
                        onChange = { filterDraft = filterDraft.copy(updatedDateRange = it) },
                    )

                    state.workshopBrowse.tagGroups.forEach { group ->
                        Text(group.label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        group.tags.forEach { tag ->
                            when (group.selectionMode) {
                                com.sultansgame.modmanager.model.WorkshopBrowseTagGroupSelectionMode.SingleSelect -> {
                                    val selected = tag.value in filterDraft.requiredTags
                                    SmallAction(if (selected) "✓ ${tag.label}" else tag.label) {
                                        val groupValues = group.tags.map { it.value }.toSet()
                                        filterDraft = filterDraft.copy(
                                            requiredTags = (filterDraft.requiredTags - groupValues) + tag.value,
                                            excludedTags = filterDraft.excludedTags - groupValues,
                                        )
                                    }
                                }
                                com.sultansgame.modmanager.model.WorkshopBrowseTagGroupSelectionMode.IncludeExclude -> {
                                    val inclusion = tag.value in filterDraft.requiredTags
                                    val exclusion = tag.value in filterDraft.excludedTags
                                    val label = when {
                                        inclusion -> "✓ 包含 ${tag.label}"
                                        exclusion -> "− 排除 ${tag.label}"
                                        else -> tag.label
                                    }
                                    SmallAction(label) {
                                        filterDraft = when {
                                            !inclusion && !exclusion -> filterDraft.copy(
                                                requiredTags = filterDraft.requiredTags + tag.value,
                                                excludedTags = filterDraft.excludedTags - tag.value,
                                            )
                                            inclusion -> filterDraft.copy(
                                                requiredTags = filterDraft.requiredTags - tag.value,
                                                excludedTags = filterDraft.excludedTags + tag.value,
                                            )
                                            else -> filterDraft.copy(excludedTags = filterDraft.excludedTags - tag.value)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    PrimaryButton("应用筛选") {
                        onBrowse(filterDraft.copy(searchText = query, page = 1).normalized())
                        showAdvancedFilters = false
                    }
                    Text("重置高级筛选", modifier = Modifier.fillMaxWidth().clickable {
                        filterDraft = com.sultansgame.modmanager.model.WorkshopBrowseQuery(searchText = query)
                    }.padding(8.dp), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun DateRangeFilterEditor(
    title: String,
    range: com.sultansgame.modmanager.model.WorkshopDateRangeFilter,
    onChange: (com.sultansgame.modmanager.model.WorkshopDateRangeFilter) -> Unit,
) {
    var start by remember(range.startEpochSeconds) { mutableStateOf(range.startEpochSeconds.takeIf { it > 0L }?.toString().orEmpty()) }
    var end by remember(range.endEpochSeconds) { mutableStateOf(range.endEpochSeconds.takeIf { it > 0L }?.toString().orEmpty()) }
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    WorkshopTextField(start, {
        start = it.filter(Char::isDigit)
        onChange(range.copy(startEpochSeconds = start.toLongOrNull() ?: 0L))
    }, "开始（留空表示不限）", numeric = true)
    WorkshopTextField(end, {
        end = it.filter(Char::isDigit)
        onChange(range.copy(endEpochSeconds = end.toLongOrNull() ?: 0L))
    }, "结束（留空表示不限）", numeric = true)
}

@Composable
private fun WorkshopTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    numeric: Boolean = false,
    password: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(14.dp),
        textStyle = androidx.compose.ui.text.TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp),
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            inner()
        },
    )
}

@Composable
private fun PatchScreen(
    wide: Boolean,
    state: ManagerUiState,
    onShowWarning: () -> Unit,
    onImportLocalApk: () -> Unit,
    onImportLocalApkSet: () -> Unit,
    onSelectInstalledGame: () -> Unit,
    onPrepare: () -> Unit,
    onRefreshPending: () -> Unit,
    onRequestUninstall: (String) -> Unit,
    onInstallPrepared: (String) -> Unit,
    onExportPreparedApks: (String) -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit,
    onRestart: () -> Unit,
    onResumePreparedPatch: (String) -> Unit,
    onUpdateConfirmation: (PatchConfirmation) -> Unit,
    onRequestPatchCleanup: (String) -> Unit,
) {
    val keyStatus = when (state.deviceSigningKeyState) {
        com.sultansgame.modmanager.model.DeviceSigningKeyState.NotCreated -> "首次准备时创建"
        com.sultansgame.modmanager.model.DeviceSigningKeyState.Ready -> "可复用"
        com.sultansgame.modmanager.model.DeviceSigningKeyState.MissingAfterMigration -> "已丢失"
        null -> "正在检查"
    }
    val exportInProgress = state.apksExport !is ApksExportUiState.Idle
    ScreenList(wide) {
        item {
            HeroPanel(
                eyebrow = "安装迁移",
                title = "按步骤准备并重装游戏",
                body = "所有输入都会先复制到 Manager 私有目录。base APK、原有 split 与 loader split 始终使用同一设备证书重签，并在安装前后校验。",
                action = "阅读安装前说明",
                onAction = onShowWarning,
            )
        }
        item { SectionLabel("设备签名密钥", keyStatus) }
        when (val patch = state.patch) {
            PatchUiState.ChooseSource -> {
                state.preparedPatchRecovery?.let { recovery ->
                    item { SectionLabel("继续未完成的安装", "修补工件已保留") }
                    item {
                        NoticeStrip(
                            "已准备的修补 APK",
                            "${recovery.summary}\n继续后仍需手动卸载原版并确认 Android 系统安装；不会自动安装。",
                        )
                    }
                    item { PrimaryButton("继续安装已准备工件") { onResumePreparedPatch(recovery.transactionId) } }
                }
                state.patchCleanup?.let { cleanup ->
                    item { SectionLabel("私有修补文件", "约 ${formatBytes(cleanup.sizeBytes)}") }
                    item {
                        NoticeStrip(
                            "可释放修补空间",
                            "删除会放弃本次修补，移除私有输入、重签 APK 与 loader 模板；不会影响已导出的 APKS、已安装游戏或存档。",
                        )
                    }
                    item {
                        PrimaryButton(
                            "删除私有修补文件",
                            !state.patchCleanupInProgress && !exportInProgress,
                        ) { onRequestPatchCleanup(cleanup.transactionId) }
                    }
                }
                item { SectionLabel("步骤 1", "选择来源") }
                item {
                    NoticeStrip(
                        "选择要修补的完整游戏包",
                        "可读取当前已安装的游戏，也可导入无 split 的单个 APK，或包含 base 与全部 split 的 APKS 文件。未知或不完整版本不会进入重签与安装。",
                    )
                }
                item {
                    PrimaryButton(
                        "使用已安装游戏",
                        state.gameProbeResult is GameProbeResult.Found,
                        onSelectInstalledGame,
                    )
                }
                item { PrimaryButton("选择本地 APK", onClick = onImportLocalApk) }
                item { PrimaryButton("选择本地 APKS", onClick = onImportLocalApkSet) }
                if (state.gameProbeResult !is GameProbeResult.Found) {
                    item { NoticeStrip("未检测到已安装游戏", "仍可选择本地 APK 或 APKS 修补；单 APK 必须是不依赖 split 的完整安装包。") }
                }
            }
            is PatchUiState.Importing -> item { LoadingPanel(patch.label) }
            is PatchUiState.Review -> {
                val input = patch.input
                val unsupported = input.classification.compatibility.compatibility == com.sultansgame.modmanager.model.Compatibility.Unsupported
                item { SectionLabel("步骤 1", "输入已检查") }
                item {
                    NoticeStrip(
                        input.sourceLabel,
                        "版本 ${input.versionLabel} · ${input.splitCount} 个原始 split · 签名 ${input.signerSummary}",
                    )
                }
                item { SectionLabel("步骤 2", if (unsupported) "不可修补" else "审阅确认") }
                item {
                    if (unsupported) {
                        ErrorPanel(input.classification.compatibility.reasons.joinToString("\n"))
                    } else {
                        NoticeStrip("已命中冻结 profile", "将只重签 base、全部原始 split 和冻结的 loader split。")
                    }
                }
                if (!unsupported) {
                    item {
                        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ConfirmationCheckbox("我已阅读安装前说明，理解这是非官方修改", patch.confirmation.acknowledgedInstallRisk) {
                                    onUpdateConfirmation(patch.confirmation.copy(acknowledgedInstallRisk = it))
                                }
                                ConfirmationCheckbox("我理解设备密钥创建后不可导出，换机需重新迁移", patch.confirmation.acknowledgedRecoveryLimit) {
                                    onUpdateConfirmation(patch.confirmation.copy(acknowledgedRecoveryLimit = it))
                                }
                                ConfirmationCheckbox("我理解必须先卸载旧签名游戏，再安装修补版本", patch.confirmation.acknowledgedReinstallRequirement) {
                                    onUpdateConfirmation(patch.confirmation.copy(acknowledgedReinstallRequirement = it))
                                }
                            }
                        }
                    }
                    item {
                        PrimaryButton(
                            "准备修补工件",
                            state.deviceSigningKeyState != com.sultansgame.modmanager.model.DeviceSigningKeyState.MissingAfterMigration &&
                                patch.confirmation.permits(input.classification.mode),
                            onPrepare,
                        )
                    }
                }
                item { PrimaryButton("重新选择来源", onClick = onRestart) }
            }
            is PatchUiState.Preparing -> item { LoadingPanel("正在重签 base、原始 split 与 loader split…") }
            is PatchUiState.AwaitingOriginalUninstall -> {
                item { SectionLabel("步骤 3", "卸载原版") }
                item { NoticeStrip("工件已准备", patch.summary) }
                when (patch.gameState) {
                    is GameProbeResult.Found -> item { PrimaryButton("打开系统卸载界面", !exportInProgress) { onRequestUninstall(patch.transactionId) } }
                    is GameProbeResult.Failed, null -> item { PrimaryButton("重新检查卸载状态", !exportInProgress, onRefreshPending) }
                    GameProbeResult.NotInstalled -> item { PrimaryButton("重新检查卸载状态", !exportInProgress, onRefreshPending) }
                }
                item { ApksExportAction(state.apksExport, patch.transactionId, onExportPreparedApks) }
            }
            is PatchUiState.ReadyToInstall -> {
                item { SectionLabel("步骤 4", "安装已准备工件") }
                item { NoticeStrip("原版已卸载", patch.summary) }
                item { PrimaryButton("调用系统安装器", !exportInProgress) { onInstallPrepared(patch.transactionId) } }
                item { ApksExportAction(state.apksExport, patch.transactionId, onExportPreparedApks) }
            }
            is PatchUiState.SubmittingInstall -> item { LoadingPanel("正在提交已准备的 APK 集合…") }
            is PatchUiState.AwaitingInstallPermission -> {
                item { SectionLabel("步骤 4", "需要安装授权") }
                item { NoticeStrip("授权后不会自动安装", "请在系统设置中允许 Manager 安装未知应用；返回后将显示独立的安装按钮。") }
                item { PrimaryButton("前往安装授权设置", !exportInProgress, onOpenUnknownSourcesSettings) }
                patch.transactionId?.let { transactionId ->
                    item { ApksExportAction(state.apksExport, transactionId, onExportPreparedApks) }
                }
            }
            is PatchUiState.AwaitingSystemInstall -> {
                item { SectionLabel("步骤 4", "等待系统安装") }
                item { LoadingPanel("请在 Android 系统界面确认安装；完成后 Manager 会验证包、版本、证书和 split 集合。") }
            }
            is PatchUiState.Completed -> {
                item { SectionLabel("迁移完成", "步骤已完成") }
                item { NoticeStrip("安装验证通过", "请退出并冷启动游戏，以验证 loader 与 Mod 支持。") }
                item { PrimaryButton("开始新的修补", onClick = onRestart) }
            }
            is PatchUiState.Failed -> {
                item { SectionLabel("修补未完成", "需要重新开始") }
                item { ErrorPanel(patch.reason) }
                item { PrimaryButton("重新选择来源", onClick = onRestart) }
            }
        }
    }
}

@Composable
private fun ConfirmationCheckbox(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckMark(checked)
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
    }
}

@Composable
private fun PatchStep(number: String, title: String, caption: String) {
    Card(Modifier.width(150.dp), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(number, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(caption, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun SettingsScreen(state: ManagerUiState, wide: Boolean, onShowDialog: (DialogKind) -> Unit) {
    ScreenList(wide) {
        item { HeroPanel("安全与透明度", "所有数据都留在可见边界内", "缓存、SAF 授权和公开下载工件位于 Manager 私有目录；当前版本不会修改游戏。") }
        item { SectionLabel("本地工作区", "${state.cachedMods.size} 个 Mod") }
        item { ListPanel("私有 Mod 缓存", "清除操作无法撤销，不会影响游戏或公开工件。", "管理") { onShowDialog(DialogKind.ClearCache) } }
        item { SectionLabel("法律与数据", "可随时查看") }
        item { ListPanel("使用须知", "非官方关联、内容权利与兼容性风险。", "查看") { onShowDialog(DialogKind.Notice) } }
        item { ListPanel("隐私与数据流", "本地保存范围、网络请求与清理方式。", "查看") { onShowDialog(DialogKind.Privacy) } }
        item { ListPanel("开源许可", "GNU GPLv3、无担保与第三方权利说明。", "查看") { onShowDialog(DialogKind.License) } }
        item { NoticeStrip("版本", "Manager 0.1.0") }
    }
}

@Composable
private fun ScreenList(wide: Boolean, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (wide) 34.dp else 18.dp, vertical = if (wide) 10.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun HeroPanel(eyebrow: String, title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null, muted: Boolean = false) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = if (muted) MiuixTheme.colorScheme.surfaceVariant else MiuixTheme.colorScheme.primaryContainer),
        insideMargin = PaddingValues(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(eyebrow, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(body, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (action != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                PrimaryButton(action, !muted, onAction)
            }
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
private fun ListPanel(title: String, body: String, trailing: String, onClick: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(17.dp), onClick = { onClick?.invoke() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(body, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.width(12.dp))
            StatusPill(trailing)
        }
    }
}

@Composable
private fun EmptyPanel(title: String, body: String) = NoticeStrip(title, body)

@Composable
private fun LoadingPanel(body: String) = NoticeStrip("正在处理", body)

@Composable
private fun ErrorPanel(body: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.errorContainer), insideMargin = PaddingValues(17.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("操作未完成", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(body, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NoticeStrip(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant), insideMargin = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(8.dp).padding(top = 5.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(body, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
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
private fun ApksExportAction(
    export: ApksExportUiState,
    transactionId: String,
    onExport: (String) -> Unit,
) {
    when (export) {
        ApksExportUiState.Idle -> PrimaryButton("导出 APKS 自行安装") { onExport(transactionId) }
        is ApksExportUiState.SelectingDestination -> {
            if (export.transactionId == transactionId) LoadingPanel("正在选择 APKS 导出位置…")
            else PrimaryButton("导出 APKS 自行安装", enabled = false) {}
        }
        is ApksExportUiState.Validating -> {
            if (export.transactionId == transactionId) LoadingPanel("正在校验已签名 APK…")
            else PrimaryButton("导出 APKS 自行安装", enabled = false) {}
        }
        is ApksExportUiState.Writing -> {
            if (export.transactionId == transactionId) {
                val progress = if (export.totalBytes > 0L) {
                    (export.writtenBytes.toFloat() / export.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "正在导出 ${export.completedArtifacts}/${export.artifactCount}：${export.artifactName}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${formatBytes(export.writtenBytes)} / ${formatBytes(export.totalBytes)} · ${(progress * 100).toInt()}%",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Box(
                            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                .background(MiuixTheme.colorScheme.surfaceVariant),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(progress).height(6.dp)
                                    .background(MiuixTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            } else {
                PrimaryButton("导出 APKS 自行安装", enabled = false) {}
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant),
        insideMargin = PaddingValues(horizontal = 15.dp, vertical = 12.dp),
        onClick = { if (enabled) onClick() },
    ) { Text(label, modifier = Modifier.fillMaxWidth(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (enabled) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary) }
}

@Composable
private fun FeedbackBanner(feedback: FeedbackMessage, onDismiss: () -> Unit, wide: Boolean) {
    Box(Modifier.fillMaxSize().padding(start = if (wide) 280.dp else 16.dp, end = 16.dp, top = if (wide) 18.dp else 72.dp), contentAlignment = Alignment.TopEnd) {
        Card(
            modifier = Modifier.width(340.dp),
            colors = CardDefaults.defaultColors(color = if (feedback.isError) MiuixTheme.colorScheme.errorContainer else MiuixTheme.colorScheme.secondaryContainer),
            insideMargin = PaddingValues(13.dp),
            onClick = onDismiss,
        ) { Text(feedback.text, fontSize = 13.sp) }
    }
}

@Composable
private fun PreparingNoticeDialog() {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) { Text(stringResource(R.string.status_preparing), fontSize = 15.sp) }
    }
}

@Composable
private fun LegalNoticeDialog(onAccept: () -> Unit, onDismiss: (() -> Unit)? = null) {
    var checked by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = DialogProperties(dismissOnBackPress = onDismiss != null, dismissOnClickOutside = onDismiss != null),
    ) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.notice_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                NoticeBullet("非官方工具", "不隶属游戏发行方、Steam 或 Valve。")
                NoticeBullet("合法内容", "仅处理你有权使用的游戏、Mod 和本地文件。")
                NoticeBullet("当前能力", "只读探测、私有缓存与公开工件下载；不会修改游戏。")
                NoticeBullet("未来风险", "安装前会要求确认已备份存档、理解兼容性风险与设备签名密钥的不可导出限制。")
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckMark(checked)
                    Spacer(Modifier.width(10.dp))
                    Text("我已阅读并理解使用须知", modifier = Modifier.weight(1f), fontSize = 14.sp)
                }
                PrimaryButton(stringResource(R.string.action_continue), checked) { onAccept(); onDismiss?.invoke() }
                if (onDismiss != null) Text(stringResource(R.string.action_cancel), modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(8.dp), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun NoticeBullet(title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("·", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(body, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun CheckMark(checked: Boolean) {
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .background(if (checked) MiuixTheme.colorScheme.primary else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) { if (checked) Text("✓", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onPrimary) }
}

@Composable
private fun TextDialog(title: String, body: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(body, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                PrimaryButton(stringResource(R.string.action_close), onClick = onDismiss)
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
                Text(stringResource(R.string.action_cancel), modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(8.dp), fontSize = 13.sp)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}
