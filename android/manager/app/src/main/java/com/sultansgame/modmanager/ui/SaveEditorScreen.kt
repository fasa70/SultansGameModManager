package com.sultansgame.modmanager.ui

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sultansgame.modmanager.ManagerUiState
import com.sultansgame.modmanager.SaveEditorStage
import com.sultansgame.modmanager.SaveEditorUiState
import com.sultansgame.modmanager.SaveEditorWebAction
import com.sultansgame.modmanager.platform.saveeditor.SaveArchiveIndex
import com.sultansgame.modmanager.platform.saveeditor.SaveBackupEntry
import com.sultansgame.modmanager.platform.saveeditor.SaveEditorArchiveSlot
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Confirmations gating actions that overwrite or delete something. */
private sealed interface SaveEditorPrompt {
    data object Leave : SaveEditorPrompt

    data object Reload : SaveEditorPrompt

    data class Restore(val entry: SaveBackupEntry) : SaveEditorPrompt

    data class DeleteBackup(val entry: SaveBackupEntry) : SaveEditorPrompt
}

/**
 * The save editor tab.
 *
 * Picking a user and a file is native; once a save is open the whole screen is
 * the vendored upstream HTML editor, with no native chrome above it. The page's
 * own toolbar drives the native side: 保存到游戏存档 runs the overwrite pipeline,
 * 重新读取 and 返回存档列表 route back here for their confirmations, and
 * 槽位 / 备份 opens the one native surface that covers the editor.
 */
@Composable
internal fun SaveEditorScreen(state: ManagerUiState, actions: ManagerActions, wide: Boolean) {
    val editor = state.saveEditor
    var prompt by remember { mutableStateOf<SaveEditorPrompt?>(null) }
    var archiveTarget by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    /** Confirms first when the page holds edits that are not on disk yet. */
    fun guardUnsaved(pending: SaveEditorPrompt, proceed: () -> Unit) {
        if (!editor.editorReady) {
            proceed()
            return
        }
        scope.launch {
            if (actions.saveEditorHasUnsavedEdits()) prompt = pending else proceed()
        }
    }

    val leave = { guardUnsaved(SaveEditorPrompt.Leave) { actions.leaveSaveFile() } }
    val reload = { guardUnsaved(SaveEditorPrompt.Reload) { actions.reloadSaveFile() } }

    // The page's 重新读取 / 返回 buttons arrive as state rather than as direct
    // calls: both discard unsaved edits, and the confirmation dialog lives here.
    LaunchedEffect(editor.pendingWebAction) {
        val pendingAction = editor.pendingWebAction ?: return@LaunchedEffect
        actions.consumeSaveEditorWebAction()
        when (pendingAction) {
            SaveEditorWebAction.Reload -> reload()
            SaveEditorWebAction.Leave -> leave()
        }
    }

    if (editor.stage == SaveEditorStage.Edit) {
        BackHandler(enabled = true) { if (editor.toolsOpen) actions.closeSaveEditorTools() else leave() }
        if (editor.toolsOpen) {
            // The panel replaces the editor rather than covering it: an overlay
            // would leak taps through its empty areas onto the page's buttons.
            // The WebView is retained, so detaching it keeps every edit intact.
            SaveEditorToolsPanel(
                editor = editor,
                wide = wide,
                onClose = actions.closeSaveEditorTools,
                onReload = reload,
                onLeave = leave,
                onSaveSlot = { slot -> archiveTarget = slot },
                onPrompt = { pending -> prompt = pending },
            )
        } else {
            SaveEditorWebHost(editor.editorGeneration, actions, Modifier.fillMaxSize())
        }
    } else {
        SaveEditorPicker(editor, actions, wide)
    }

    archiveTarget?.let { slot ->
        ArchiveSlotSaveDialog(
            slot = slot,
            existing = editor.archiveSlots.getOrNull(slot),
            busy = editor.isBusy,
            onDismiss = { archiveTarget = null },
            onConfirm = { name ->
                actions.saveSaveArchive(slot, name)
                archiveTarget = null
                actions.closeSaveEditorTools()
            },
        )
    }
    prompt?.let { pending ->
        SaveEditorPromptDialog(
            prompt = pending,
            editor = editor,
            actions = actions,
            onDismiss = { prompt = null },
        )
    }
}

/** 承载常驻 WebView。视图归 ViewModel 所有，跨 tab 切换与旋转都不重建。 */
@Composable
private fun SaveEditorWebHost(generation: Int, actions: ManagerActions, modifier: Modifier) {
    val context = LocalContext.current
    // generation 只在旧视图被销毁（渲染进程崩溃）时变化；用 key 换掉整个
    // AndroidView 节点，否则 factory 不会重新执行，界面上会留着已销毁的视图。
    key(generation) {
        // attach() 把视图从上一个父容器摘下来并借用当前 Activity context，
        // 所以每次进入本节点都要调用一次。
        val view: View? = remember(context) {
            runCatching { actions.attachSaveEditorView(context) }.getOrNull()
        }
        if (view == null) {
            Column(modifier) { NoticeStrip("编辑器不可用", "无法创建编辑器页面，请退出后重新进入存档编辑。") }
        } else {
            AndroidView(
                factory = { view },
                modifier = modifier,
                onRelease = { actions.detachSaveEditorView() },
            )
        }
    }
}

/**
 * 槽位保存与备份管理。
 *
 * 编辑阶段整屏都是 WebView，这些动作没有别的落脚处，所以由页面上被改造的
 * 「导出备份」按钮唤出本页；打开时替换而非叠加编辑器，避免触摸穿透。
 * WebView 是常驻的，摘下来不会丢失任何编辑。重新读取与返回在页面工具栏上也有，
 * 这里同样保留一份——载入失败时页面无法显示错误，本页是唯一的落脚处。
 */
@Composable
private fun SaveEditorToolsPanel(
    editor: SaveEditorUiState,
    wide: Boolean,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onLeave: () -> Unit,
    onSaveSlot: (Int) -> Unit,
    onPrompt: (SaveEditorPrompt) -> Unit,
) {
    ScreenList(wide) {
        item {
            HeroPanel(
                eyebrow = editor.selectedFile ?: "存档",
                title = "槽位 / 备份",
                body = "用户 ${editor.selectedUser.orEmpty()}。这里的操作都会直接写入游戏存档目录，" +
                    "覆盖前会自动备份。编辑器里未保存的修改不会因为打开本页而丢失。",
                action = "返回编辑",
                actionEnabled = !editor.isBusy,
                onAction = onClose,
            )
        }
        // Every path that sets `error` fails closed before writing, so the file on
        // disk is untouched — which is the first thing the player needs told.
        editor.error?.let { error -> item { NoticeStrip("存档未改动", error) } }
        editor.notice?.let { notice -> item { NoticeStrip("提示", notice) } }
        if (editor.isBusy) item { LoadingPanel(editor.progress ?: "正在处理存档…") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction("重新读取存档", enabled = !editor.isBusy, onClick = onReload)
                SmallAction("返回存档列表", enabled = !editor.isBusy, onClick = onLeave)
            }
        }
        item { SectionLabel("存档槽位", "${SaveArchiveIndex.SLOT_COUNT} 个") }
        item {
            NoticeStrip(
                "保存到槽位",
                "槽位就是游戏读档界面里的十个位置。保存会把编辑器里的当前内容写成一个读档位并更新" +
                    "读档索引，正在编辑的存档文件本身不受影响。",
            )
        }
        (0 until SaveArchiveIndex.SLOT_COUNT).forEach { slot ->
            item("save-slot-$slot") {
                ArchiveSlotRow(
                    slot = slot,
                    summary = editor.archiveSlots.getOrNull(slot),
                    busy = editor.isBusy,
                    canWrite = editor.editorReady,
                    onSave = { onSaveSlot(slot) },
                )
            }
        }
        item { SectionLabel("存档备份", "${editor.backups.size} 份") }
        if (editor.backups.isEmpty()) {
            item {
                EmptyPanel(
                    "还没有备份",
                    "每次覆盖保存或恢复备份前，本应用都会先把当前存档另存一份，最多保留最近 10 份。",
                )
            }
        }
        items(editor.backups, key = { "save-backup-${it.path}" }) { entry ->
            BackupRow(
                entry = entry,
                busy = editor.isBusy,
                onRestore = { onPrompt(SaveEditorPrompt.Restore(entry)) },
                onDelete = { onPrompt(SaveEditorPrompt.DeleteBackup(entry)) },
            )
        }
        item { SectionLabel("使用说明", "适配自上游 HTML 版") }
        item { SaveEditorHelpPanel() }
    }
}

/** 选用户 / 选存档 阶段，沿用其他页面的列表布局。 */
@Composable
private fun SaveEditorPicker(editor: SaveEditorUiState, actions: ManagerActions, wide: Boolean) {
    ScreenList(wide) {
        item { SaveEditorHeroPanel() }
        editor.error?.let { error -> item { NoticeStrip("存档编辑不可用", error) } }
        editor.notice?.let { notice -> item { NoticeStrip("提示", notice) } }
        if (editor.isBusy) item { LoadingPanel(editor.progress ?: "正在读取存档…") }
        when (editor.stage) {
            SaveEditorStage.SelectUser -> SaveUserStage(editor.users, editor.isBusy, actions)
            SaveEditorStage.SelectFile -> SaveFileStage(editor, actions)
            SaveEditorStage.Edit -> Unit
        }
        item { SectionLabel("使用说明", "适配自上游 HTML 版") }
        item { SaveEditorHelpPanel() }
    }
}

private fun LazyListScope.SaveUserStage(
    users: List<String>,
    busy: Boolean,
    actions: ManagerActions,
) {
    item { SectionLabel("选择游戏用户", "${users.size} 个") }
    if (users.isEmpty() && !busy) item { EmptyPanel("没有找到存档用户", "请先启动游戏并创建至少一个存档。") }
    items(users, key = { "save-user-$it" }) { uid ->
        ListPanel(
            title = "用户 $uid",
            body = "SAVEDATA/$uid",
            trailing = "选择",
            enabled = !busy,
        ) { actions.selectSaveUser(uid) }
    }
}

private fun LazyListScope.SaveFileStage(editor: SaveEditorUiState, actions: ManagerActions) {
    val busy = editor.isBusy
    // user_archive.json 是读档索引而不是存档本体，编辑它没有意义。
    val files = editor.saveFiles.filterNot { it == "user_archive.json" }
    item { SecondaryButton("返回用户列表", enabled = !busy, onClick = actions.loadSaveUsers) }
    item { SectionLabel("选择存档文件", "${files.size} 个") }
    if (files.isEmpty() && !busy) item { EmptyPanel("没有找到 JSON 存档", "该用户目录中没有可编辑的存档文件。") }
    items(files, key = { "save-file-$it" }) { file ->
        val slotName = SaveArchiveIndex.slotOfFileName(file)
            ?.let { slot -> editor.archiveSlots.getOrNull(slot)?.name }
        ListPanel(
            title = if (slotName != null) "$file（$slotName）" else file,
            body = "${saveFileKindLabel(file)} · 用户 ${editor.selectedUser.orEmpty()}",
            trailing = "编辑",
            enabled = !busy,
        ) { actions.selectSaveFile(file) }
    }
}

private fun saveFileKindLabel(file: String): String = when {
    file == "auto_save.json" -> "自动存档"
    file == "global.json" -> "全局存档"
    file.endsWith("_end.json") -> "回合结束存档"
    file.startsWith("round_") -> "回合存档"
    file.startsWith("USERARCHIVE/") -> "存档槽位"
    else -> "JSON 存档"
}

/** One of the game's ten load-menu slots, with its overwrite action. */
@Composable
private fun ArchiveSlotRow(
    slot: Int,
    summary: SaveEditorArchiveSlot?,
    busy: Boolean,
    canWrite: Boolean,
    onSave: () -> Unit,
) {
    CardSurface {
        val label = "%03d".format(slot + 1)
        if (summary == null) {
            TextBlock("$label （空）")
        } else {
            TextBlock("$label ${summary.name}")
            TextBlock(
                "存活天数：${summary.liveDays}天 | 苏丹卡剩余：${summary.leftSudan} | 处刑日残余：${summary.executionDay}天",
            )
            TextBlock(summary.saveTimeText)
        }
        SmallAction(
            label = if (summary == null) "保存到此槽位" else "覆盖此槽位",
            enabled = !busy && canWrite,
            onClick = onSave,
        )
    }
}

@Composable
private fun ArchiveSlotSaveDialog(
    slot: Int,
    existing: SaveEditorArchiveSlot?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "未命名存档") }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("保存到第 ${slot + 1} 个槽位", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (existing != null) {
                    TextBlock("该槽位已存在存档「${existing.name}」，继续将覆盖它；覆盖前会先备份一份，可以恢复。")
                }
                TextBlock("编辑器里当前的内容会写入该槽位；正在编辑的存档文件本身不会被改动。")
                LabeledTextField(name, { name = it }, "存档名称")
                PrimaryButton(if (existing != null) "覆盖并保存" else "保存", enabled = !busy) { onConfirm(name) }
                SecondaryButton("取消", onClick = onDismiss)
            }
        }
    }
}

/** One manager-side snapshot with its restore/delete actions. */
@Composable
private fun BackupRow(
    entry: SaveBackupEntry,
    busy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    CardSurface {
        TextBlock(entry.createdAtText)
        TextBlock("${entry.fileName} · ${(entry.sizeBytes + 1023) / 1024} KiB")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("恢复此备份", enabled = !busy, onClick = onRestore)
            SmallAction("删除", enabled = !busy, onClick = onDelete)
        }
    }
}

/**
 * The leave/reload/restore/delete confirmations. Every branch names the file it
 * is about to touch and states what remains recoverable afterwards, because a
 * wrong answer here costs the player their progress.
 */
@Composable
private fun SaveEditorPromptDialog(
    prompt: SaveEditorPrompt,
    editor: SaveEditorUiState,
    actions: ManagerActions,
    onDismiss: () -> Unit,
) {
    val fileName = editor.selectedFile ?: "存档文件"
    when (prompt) {
        SaveEditorPrompt.Leave -> ConfirmDialog(
            title = "放弃未保存的修改",
            lines = listOf(
                "$fileName 在编辑器里还有尚未写入的修改。",
                "返回存档列表会丢弃这些修改，磁盘上的存档文件不受影响。",
            ),
            confirmLabel = "丢弃修改并返回",
            busy = editor.isBusy,
            onConfirm = {
                actions.leaveSaveFile()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        SaveEditorPrompt.Reload -> ConfirmDialog(
            title = "重新读取存档",
            lines = listOf(
                "将重新从游戏目录读取 $fileName。",
                "尚未保存的修改会全部丢弃，此操作不影响已写入的存档文件。",
            ),
            confirmLabel = "丢弃修改并重新读取",
            busy = editor.isBusy,
            onConfirm = {
                actions.reloadSaveFile()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        is SaveEditorPrompt.Restore -> ConfirmDialog(
            title = "恢复备份",
            lines = listOf(
                "将用 ${prompt.entry.createdAtText} 的备份覆盖 ${prompt.entry.fileName}。",
                "恢复前会先把当前内容另存为一份新备份，因此这一步同样可以撤回。",
                "编辑器里尚未保存的修改会被丢弃。恢复后请重新进入游戏读取存档。",
            ),
            confirmLabel = "恢复此备份",
            busy = editor.isBusy,
            onConfirm = {
                actions.restoreSaveBackup(prompt.entry)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        is SaveEditorPrompt.DeleteBackup -> ConfirmDialog(
            title = "删除备份",
            lines = listOf(
                "将删除 ${prompt.entry.createdAtText} 的备份（${prompt.entry.fileName}）。",
                "删除后无法找回这一份备份，存档文件本身不受影响。",
            ),
            confirmLabel = "删除备份",
            busy = editor.isBusy,
            onConfirm = {
                actions.deleteSaveBackup(prompt.entry)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    lines: List<String>,
    confirmLabel: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                lines.forEach { line -> TextBlock(line) }
                PrimaryButton(confirmLabel, enabled = !busy, onClick = onConfirm)
                SecondaryButton("取消", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun SaveEditorHelpPanel() {
    var expanded by remember { mutableStateOf(false) }
    CardSurface {
        SmallAction(if (expanded) "收起使用说明" else "展开使用说明") { expanded = !expanded }
        if (!expanded) {
            TextBlock("修改存档前请先在游戏内“保存并退出”；覆盖保存前会自动备份，可在“槽位 / 备份”里一键恢复。")
            return@CardSurface
        }
        TextBlock("编辑器页面上方的按钮")
        TextBlock("· 【💾 保存到游戏存档】把当前内容写回你打开的那个存档文件，覆盖前自动备份。")
        TextBlock("· 【🗂️ 槽位 / 备份】打开本应用的面板：保存到读档槽位、恢复或删除备份。")
        TextBlock("· 【🔄 重新读取】丢弃未保存的修改，重新从游戏目录读取当前存档。")
        TextBlock("· 【⬅️ 返回存档列表】回到本页的存档选择界面；有未保存的修改时会先确认。")
        TextBlock("· 具体字段含义、卡牌与仪式的编辑说明请看编辑器页面内的提示，本应用不改动它的编辑逻辑。")
        TextBlock("来源与致谢")
        TextBlock("· 编辑界面为内置的《苏丹的游戏》存档编辑器网页版（作者 柳漪春涛，GPL-3.0-or-later）")
        TextBlock("· 本应用为其 Android 集成与适配，非官方工具，与游戏开发商无关。")
    }
}

/**
 * The tab's opening card. Attribution rides in the hero panel — matching the
 * merge tab — rather than in a separate card: upstream is GPLv3, so the notice
 * is an obligation as well as a credit, and it should be the first thing read.
 * The repo-level THIRD_PARTY_NOTICES.md carries the full declaration.
 */
@Composable
private fun SaveEditorHeroPanel() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer),
        insideMargin = PaddingValues(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                "存档编辑器",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text("编辑游戏存档", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "先选择游戏用户，再选择存档文件。修改前请先在游戏内“保存并退出”；覆盖前会自动备份，可随时恢复。\n" +
                    "编辑界面内置了 @柳漪春涛 老师开发的《苏丹的游戏》存档修改器 · " +
                    "并把“选存档 / 读取 / 覆盖写回 / 自动备份” 功能接到了管理器上，让用户无需手动导入导出文件，即可轻松修改存档！。" +
                    "如果有能力，请点击链接去给这位老师的仓库点亮颗star！",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            ExternalLinkText(SAVE_EDITOR_REFERENCE_URL)
        }
    }
}

private const val SAVE_EDITOR_REFERENCE_URL = "https://github.com/khb10533/suyou-save-editor"

@Composable
private fun CardSurface(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(14.dp),
        onClick = onClick,
    ) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
private fun TextBlock(value: String) {
    Text(value, fontSize = 13.sp)
}
