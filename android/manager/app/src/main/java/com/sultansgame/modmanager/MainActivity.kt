package com.sultansgame.modmanager

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    Game(R.string.nav_game, "01", "安装状态"),
    Mods(R.string.nav_mods, "02", "私有缓存"),
    Workshop(R.string.nav_workshop, "03", "公开工件"),
    Patch(R.string.nav_patch, "04", "安装迁移"),
    Settings(R.string.nav_settings, "05", "安全与关于"),
}

private enum class DialogKind { Notice, Privacy, License, ClearCache, SyncMods, PatchWarning }

class MainActivity : ComponentActivity() {
    private val viewModel by lazy { ViewModelProvider(this)[ManagerViewModel::class.java] }
    private val selectModZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importZip)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ManagerApp(
                    state = state,
                    onRefreshGame = viewModel::refreshGame,
                    onImportMod = { selectModZip.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                    onLookupWorkshop = viewModel::lookupWorkshop,
                    onBeginSteamLogin = viewModel::beginSteamLogin,
                    onSubmitSteamGuard = viewModel::submitSteamGuard,
                    onLogoutSteam = viewModel::logoutSteam,
                    onSearchWorkshop = viewModel::searchWorkshop,
                    onQueueWorkshopDownload = viewModel::queueWorkshopDownload,
                    onRetryWorkshopDownload = viewModel::retryWorkshopDownload,
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
                    onBeginPatching = viewModel::beginPatching,
                    onUpdatePatchConfirmation = viewModel::updatePatchConfirmation,
                )
            }
        }
    }
}

@Composable
private fun ManagerApp(
    state: ManagerUiState,
    onRefreshGame: () -> Unit,
    onImportMod: () -> Unit,
    onLookupWorkshop: (String) -> Unit,
    onBeginSteamLogin: (String, String) -> Unit,
    onSubmitSteamGuard: (String) -> Unit,
    onLogoutSteam: () -> Unit,
    onSearchWorkshop: (String, Int) -> Unit,
    onQueueWorkshopDownload: (com.sultansgame.modmanager.model.WorkshopItem) -> Unit,
    onRetryWorkshopDownload: (String) -> Unit,
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
    onBeginPatching: () -> Unit,
    onUpdatePatchConfirmation: (PatchConfirmation) -> Unit,
) {
    var destinationIndex by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
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
                    onRefreshGame = onRefreshGame,
                    onImportMod = onImportMod,
                    onLookupWorkshop = onLookupWorkshop,
                    onBeginSteamLogin = onBeginSteamLogin,
                    onSubmitSteamGuard = onSubmitSteamGuard,
                    onLogoutSteam = onLogoutSteam,
                    onSearchWorkshop = onSearchWorkshop,
                    onQueueWorkshopDownload = onQueueWorkshopDownload,
                    onRetryWorkshopDownload = onRetryWorkshopDownload,
                    onCancelWorkshopDownload = onCancelWorkshopDownload,
                    onConfirmWorkshopImport = onConfirmWorkshopImport,
                    onDiscardWorkshopArtifact = onDiscardWorkshopArtifact,
                    onRefreshGameMods = onRefreshGameMods,
                    onSetModEnabled = onSetModEnabled,
                    onMoveMod = onMoveMod,
                    onSyncMods = onSyncMods,
                    onShowDialog = { dialog = it },
                    onBeginPatching = onBeginPatching,
                    onUpdatePatchConfirmation = onUpdatePatchConfirmation,
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
                    onRefreshGame = onRefreshGame,
                    onImportMod = onImportMod,
                    onLookupWorkshop = onLookupWorkshop,
                    onBeginSteamLogin = onBeginSteamLogin,
                    onSubmitSteamGuard = onSubmitSteamGuard,
                    onLogoutSteam = onLogoutSteam,
                    onSearchWorkshop = onSearchWorkshop,
                    onQueueWorkshopDownload = onQueueWorkshopDownload,
                    onRetryWorkshopDownload = onRetryWorkshopDownload,
                    onCancelWorkshopDownload = onCancelWorkshopDownload,
                    onConfirmWorkshopImport = onConfirmWorkshopImport,
                    onDiscardWorkshopArtifact = onDiscardWorkshopArtifact,
                    onRefreshGameMods = onRefreshGameMods,
                    onSetModEnabled = onSetModEnabled,
                    onMoveMod = onMoveMod,
                    onSyncMods = onSyncMods,
                    onShowDialog = { dialog = it },
                    onBeginPatching = onBeginPatching,
                    onUpdatePatchConfirmation = onUpdatePatchConfirmation,
                )
                CompactNavigation(destinationIndex) { destinationIndex = it }
            }
        }
        state.feedback?.let { FeedbackBanner(it, onClearFeedback, wideLayout) }
    }

    if (state.noticeAccepted == null) PreparingNoticeDialog()
    else if (state.noticeAccepted == false) LegalNoticeDialog(onAcceptNotice)

    when (dialog) {
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
    onRefreshGame: () -> Unit,
    onImportMod: () -> Unit,
    onLookupWorkshop: (String) -> Unit,
    onBeginSteamLogin: (String, String) -> Unit,
    onSubmitSteamGuard: (String) -> Unit,
    onLogoutSteam: () -> Unit,
    onSearchWorkshop: (String, Int) -> Unit,
    onQueueWorkshopDownload: (com.sultansgame.modmanager.model.WorkshopItem) -> Unit,
    onRetryWorkshopDownload: (String) -> Unit,
    onCancelWorkshopDownload: (String) -> Unit,
    onConfirmWorkshopImport: (String) -> Unit,
    onDiscardWorkshopArtifact: (String) -> Unit,
    onRefreshGameMods: () -> Unit,
    onSetModEnabled: (String, Boolean) -> Unit,
    onMoveMod: (String, Int) -> Unit,
    onSyncMods: (Boolean) -> Unit,
    onShowDialog: (DialogKind) -> Unit,
    onBeginPatching: () -> Unit,
    onUpdatePatchConfirmation: (PatchConfirmation) -> Unit,
) {
    Column(modifier) {
        if (wideLayout) ContentHeader(destination)
        when (destination) {
            Destination.Game -> GameScreen(state, wideLayout, onRefreshGame)
            Destination.Mods -> ModsScreen(
                state = state,
                wide = wideLayout,
                onImport = onImportMod,
                onRefreshGame = onRefreshGameMods,
                onSetEnabled = onSetModEnabled,
                onMove = onMoveMod,
                onSync = { onShowDialog(DialogKind.SyncMods) },
            )
            Destination.Workshop -> WorkshopScreen(
                state = state,
                wide = wideLayout,
                onLookup = onLookupWorkshop,
                onBeginSteamLogin = onBeginSteamLogin,
                onSubmitSteamGuard = onSubmitSteamGuard,
                onLogoutSteam = onLogoutSteam,
                onSearch = onSearchWorkshop,
                onQueueDownload = onQueueWorkshopDownload,
                onRetryDownload = onRetryWorkshopDownload,
                onCancelDownload = onCancelWorkshopDownload,
                onConfirmImport = onConfirmWorkshopImport,
                onDiscardArtifact = onDiscardWorkshopArtifact,
            )
            Destination.Patch -> PatchScreen(
                wide = wideLayout,
                state = state,
                onShowWarning = { onShowDialog(DialogKind.PatchWarning) },
                onBeginPatching = onBeginPatching,
                onUpdateConfirmation = onUpdatePatchConfirmation,
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
private fun GameScreen(state: ManagerUiState, wide: Boolean, onRefresh: () -> Unit) {
    val gameDetails = when (val result = state.gameProbeResult) {
        null -> stringResource(R.string.status_reading_game)
        GameProbeResult.NotInstalled -> stringResource(R.string.status_game_missing)
        is GameProbeResult.Failed -> result.reason
        is GameProbeResult.Found -> "${result.snapshot.packageName}\n版本 ${result.snapshot.versionName ?: result.snapshot.versionCode} · ${result.snapshot.signerDigestsSha256.size} 个签名摘要"
    }
    ScreenList(wide) {
        item {
            HeroPanel(
                eyebrow = "安装检查",
                title = if (state.gameProbeResult is GameProbeResult.Found) "已识别游戏安装" else "等待游戏安装信息",
                body = "已安装游戏的包名、版本、ABI 与签名证书只读探测。安装迁移在「修补」页签操作。",
                action = stringResource(R.string.action_refresh),
                onAction = onRefresh,
            )
        }
        item {
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MetricCard("游戏包", gameDetails, Modifier.weight(1f))
                MetricCard("Loader", state.loaderStatus?.let { "${it.state}\n失败码：${it.failure}" } ?: stringResource(R.string.status_loader_pending), Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("游戏包", gameDetails, Modifier.fillMaxWidth())
                MetricCard("Loader", state.loaderStatus?.let { "${it.state}\n失败码：${it.failure}" } ?: stringResource(R.string.status_loader_pending), Modifier.fillMaxWidth())
            }
        }
        item { NoticeStrip("安全边界", stringResource(R.string.game_read_only)) }
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
private fun WorkshopScreen(
    state: ManagerUiState,
    wide: Boolean,
    onLookup: (String) -> Unit,
    onBeginSteamLogin: (String, String) -> Unit,
    onSubmitSteamGuard: (String) -> Unit,
    onLogoutSteam: () -> Unit,
    onSearch: (String, Int) -> Unit,
    onQueueDownload: (com.sultansgame.modmanager.model.WorkshopItem) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onConfirmImport: (String) -> Unit,
    onDiscardArtifact: (String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var guardCode by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var publishedFileId by remember { mutableStateOf("") }
    val signedIn = state.steamAuthState as? com.sultansgame.modmanager.model.SteamAuthState.SignedIn

    ScreenList(wide) {
        item {
            HeroPanel(
                eyebrow = "STEAM WORKSHOP · APPID 3117820",
                title = "苏丹的游戏创意工坊",
                body = "搜索、登录和下载仅面向《苏丹的游戏》。下载先进入私有暂存区，只有确认后才会校验并缓存 Mod；不会写入游戏目录。",
            )
        }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (val auth = state.steamAuthState) {
                        is com.sultansgame.modmanager.model.SteamAuthState.SignedIn -> {
                            Text("已登录 Steam", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("${auth.accountName} · ${auth.steamId}", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            PrimaryButton("退出 Steam", onClick = onLogoutSteam)
                        }
                        is com.sultansgame.modmanager.model.SteamAuthState.SteamGuardRequired -> {
                            Text("需要 Steam Guard：${auth.challenge}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            WorkshopTextField(guardCode, { guardCode = it }, "验证码", numeric = false)
                            PrimaryButton("提交验证码", guardCode.isNotBlank()) { onSubmitSteamGuard(guardCode) }
                        }
                        is com.sultansgame.modmanager.model.SteamAuthState.AwaitingConfirmation -> {
                            Text("请在 Steam 中完成确认：${auth.challenge}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            NoticeStrip("等待确认", "完成确认后返回此页，再次登录即可刷新会话。")
                        }
                        com.sultansgame.modmanager.model.SteamAuthState.SigningIn -> LoadingPanel("正在连接 Steam…")
                        else -> {
                            Text("登录 Steam", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("账号密码只用于本次认证；持久化的会话由 Android Keystore 加密保护。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            WorkshopTextField(username, { username = it }, "Steam 账号")
                            WorkshopTextField(password, { password = it }, "Steam 密码", password = true)
                            PrimaryButton("登录 Steam", username.isNotBlank() && password.isNotEmpty()) {
                                onBeginSteamLogin(username, password)
                                password = ""
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("搜索创意工坊", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    WorkshopTextField(query, { query = it }, "关键词")
                    PrimaryButton("搜索", query.isNotBlank() && signedIn != null) { onSearch(query, 1) }
                    if (signedIn == null) Text("登录后可使用 Steam CM 搜索。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
        when (val search = state.workshopSearch) {
            WorkshopSearchUiState.Loading -> item { LoadingPanel("正在搜索 Steam 创意工坊…") }
            is WorkshopSearchUiState.Error -> item { ErrorPanel(search.reason) }
            is WorkshopSearchUiState.Results -> {
                item { SectionLabel("搜索结果", "第 ${search.page} 页") }
                items(search.items, key = { it.publishedFileId.toString() }) { item ->
                    ListPanel(item.title, "${item.authorName} · ${item.declaredSizeBytes?.let(::formatBytes) ?: "大小未知"}", "加入队列") { onQueueDownload(item) }
                }
                if (search.hasNextPage) item { PrimaryButton("加载更多") { onSearch(query, search.page + 1) } }
            }
            WorkshopSearchUiState.Idle -> Unit
        }
        when (val workshop = state.workshop) {
            WorkshopUiState.Idle -> Unit
            WorkshopUiState.Loading -> item { LoadingPanel("正在读取 Workshop 详情…") }
            is WorkshopUiState.Item -> item {
                ListPanel(workshop.item.title, "${workshop.item.availability} · ${workshop.item.declaredSizeBytes?.let(::formatBytes) ?: "大小未知"}", "加入队列") { onQueueDownload(workshop.item) }
            }
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
                        } else if (task.stage == com.sultansgame.modmanager.model.DownloadStage.Failed || task.stage == com.sultansgame.modmanager.model.DownloadStage.NeedsLogin) {
                            PrimaryButton("重试") { onRetryDownload(task.id) }
                        } else if (task.stage !in setOf(com.sultansgame.modmanager.model.DownloadStage.Imported, com.sultansgame.modmanager.model.DownloadStage.Cancelled)) {
                            PrimaryButton("取消下载") { onCancelDownload(task.id) }
                        }
                    }
                }
            }
        }
    }
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
    onBeginPatching: () -> Unit,
    onUpdateConfirmation: (PatchConfirmation) -> Unit,
) {
    val deviceKeyState = state.deviceSigningKeyState
    val keyStatus = when (deviceKeyState) {
        com.sultansgame.modmanager.model.DeviceSigningKeyState.NotCreated -> "首次迁移时创建"
        com.sultansgame.modmanager.model.DeviceSigningKeyState.Ready -> "可复用"
        com.sultansgame.modmanager.model.DeviceSigningKeyState.MissingAfterMigration -> "已丢失"
        null -> "正在检查"
    }
    val classification = state.patchClassification
    val isExperimental = classification?.mode == com.sultansgame.modmanager.model.PatchMode.Experimental
    val inputStatus = when (classification?.mode) {
        com.sultansgame.modmanager.model.PatchMode.Verified -> "已验证 · 官方 1.0.5"
        com.sultansgame.modmanager.model.PatchMode.Experimental -> "未验证版本"
        null -> "等待读取游戏"
    }
    val confirmation = state.patchConfirmation
    val canBegin = classification != null &&
        deviceKeyState != com.sultansgame.modmanager.model.DeviceSigningKeyState.MissingAfterMigration &&
        state.gameProbeResult is com.sultansgame.modmanager.platform.game.GameProbeResult.Found &&
        !state.patchInProgress &&
        confirmation.permits(classification?.mode ?: return)
    val patchStage = state.patchStage
    val isInProgress = state.patchInProgress || patchStage == com.sultansgame.modmanager.model.PatchStage.AwaitingSystemInstall

    ScreenList(wide) {
        item {
            HeroPanel(
                eyebrow = "安装迁移",
                title = "设备专用签名与 split 模板已冻结",
                body = "首次迁移会在此设备的 Android Keystore 创建不可导出的 RTA-4096 签名密钥。base APK、原有 split 与 loader split 使用同一设备证书重签并通过 v1+v2 验证。",
                action = "阅读安装前说明",
                onAction = onShowWarning,
            )
        }
        item { SectionLabel("设备签名密钥", keyStatus) }
        item {
            when (deviceKeyState) {
                com.sultansgame.modmanager.model.DeviceSigningKeyState.MissingAfterMigration -> ErrorPanel(
                    "此前迁移记录存在，但 Android Keystore 中的设备签名密钥已丢失。必须卸载旧迁移版游戏后重新迁移，不能直接覆盖更新。",
                )
                com.sultansgame.modmanager.model.DeviceSigningKeyState.NotCreated -> NoticeStrip(
                    "尚未创建密钥",
                    "仅浏览本页不会创建密钥。开始迁移时才会在本机生成，不会上传或导出。",
                )
                com.sultansgame.modmanager.model.DeviceSigningKeyState.Ready -> NoticeStrip(
                    "密钥可复用",
                    "后续迁移会使用同一设备证书；私钥不会离开 Android Keystore。",
                )
                null -> LoadingPanel("正在检查设备签名状态…")
            }
        }
        item { SectionLabel("目标游戏 profile", inputStatus) }
        item {
            if (isExperimental) {
                ErrorPanel(
                    "未命中已冻结的官方 profile。将以本机签名重签并安装，但兼容性未经验证，可能出现闪退、数据异常或无法启动。\n\n" +
                        (classification?.compatibility?.reasons?.joinToString("\n") ?: ""),
                )
            } else {
                NoticeStrip(
                    "版本保护",
                    classification?.compatibility?.reasons?.joinToString("\n")
                        ?: "已命中官方签名、versionCode 与 native 指纹。",
                )
            }
        }
        item { SectionLabel("安装前检查", if (isInProgress) "进行中…" else "待确认") }
        item {
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PatchStep("01", "检查 APK", "版本、ABI 与签名")
                PatchStep("02", "验证 Split", "固定模板与 native 摘要")
                PatchStep("03", "系统重装", "用户确认卸载后安装")
                PatchStep("04", "启动验证", "Provider 与 Loader 状态")
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PatchStep("01", "检查 APK", "版本、ABI 与签名")
                PatchStep("02", "验证 Split", "固定模板与 native 摘要")
                PatchStep("03", "系统重装", "用户确认卸载后安装")
                PatchStep("04", "启动验证", "Provider 与 Loader 状态")
            }
        }
        item {
            NoticeStrip(
                "首次迁移需要系统卸载确认",
                "官方游戏签名与设备签名不同，Android 不允许直接覆盖。Manager 会在私有目录保存完整 APK 集；你必须在系统界面确认卸载官方游戏后，才能安装设备签名的 base 与 loader split。",
            )
        }

        // Confirmation checkboxes
        item { SectionLabel("迁移前确认", "${if (isExperimental) 5 else 3} 项") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConfirmationCheckbox("我已阅读安装前说明，理解这是非官方修改", confirmation.acknowledgedInstallRisk) {
                        onUpdateConfirmation(confirmation.copy(acknowledgedInstallRisk = it))
                    }
                    ConfirmationCheckbox("我理解设备密钥创建后不可导出，换机需重新迁移", confirmation.acknowledgedRecoveryLimit) {
                        onUpdateConfirmation(confirmation.copy(acknowledgedRecoveryLimit = it))
                    }
                    ConfirmationCheckbox("我理解首次迁移需先卸载原游戏，再安装设备签名版本", confirmation.acknowledgedReinstallRequirement) {
                        onUpdateConfirmation(confirmation.copy(acknowledgedReinstallRequirement = it))
                    }
                    if (isExperimental) {
                        ConfirmationCheckbox("我已备份游戏存档到安全位置", confirmation.confirmedBackup) {
                            onUpdateConfirmation(confirmation.copy(confirmedBackup = it))
                        }
                        ConfirmationCheckbox("我理解此版本未经验证，兼容性无法保证", confirmation.confirmedExperimentalRetry) {
                            onUpdateConfirmation(confirmation.copy(confirmedExperimentalRetry = it))
                        }
                    }
                }
            }
        }

        // Action button
        item {
            if (isInProgress) {
                LoadingPanel(state.patchStatus ?: "正在处理安装事务…")
            } else if (patchStage == com.sultansgame.modmanager.model.PatchStage.Completed) {
                NoticeStrip("迁移完成", state.patchStatus ?: "请退出并冷启动游戏以加载 Mod 支持。")
            } else if (patchStage == com.sultansgame.modmanager.model.PatchStage.Failed) {
                ErrorPanel(state.patchStatus ?: "迁移未完成，请检查日志后重试。")
            }
        }
        item {
            PrimaryButton(
                label = "开始迁移",
                enabled = canBegin,
                onClick = onBeginPatching,
            )
        }
        if (!canBegin && classification != null && !isInProgress) {
            val missing = buildList {
                if (deviceKeyState == com.sultansgame.modmanager.model.DeviceSigningKeyState.MissingAfterMigration) add("设备密钥已丢失")
                if (state.gameProbeResult !is com.sultansgame.modmanager.platform.game.GameProbeResult.Found) add("游戏未安装")
                if (!confirmation.acknowledgedInstallRisk) add("安装风险")
                if (!confirmation.acknowledgedRecoveryLimit) add("密钥限制")
                if (!confirmation.acknowledgedReinstallRequirement) add("卸载确认")
                if (isExperimental && !confirmation.confirmedBackup) add("存档备份")
                if (isExperimental && !confirmation.confirmedExperimentalRetry) add("兼容性风险")
            }
            item { NoticeStrip("迁移尚未满足条件", missing.joinToString("、")) }
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
private fun MetricCard(title: String, body: String, modifier: Modifier) {
    Card(modifier, insideMargin = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(body, fontSize = 15.sp)
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
