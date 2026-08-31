package com.sultansgame.modmanager

import com.sultansgame.modmanager.platform.saveeditor.SaveArchiveIndex
import com.sultansgame.modmanager.platform.saveeditor.SaveBackupEntry
import com.sultansgame.modmanager.platform.saveeditor.SaveEditorArchiveSlot

enum class SaveEditorStage { SelectUser, SelectFile, Edit }

/**
 * An action the page's own toolbar asked for. It cannot be run straight away:
 * both discard unsaved edits, and the confirmation lives in the composition, so
 * the request is parked here until the UI picks it up.
 */
enum class SaveEditorWebAction { Reload, Leave }

/**
 * State for the save editor tab.
 *
 * The editing surface itself is the vendored HTML editor running in a WebView,
 * so there is no per-field edit buffer here: the in-progress save lives only as
 * JavaScript state inside the page. What this app owns is which file is open,
 * the text it read from disk, the text it last wrote, and the backups it took.
 */
data class SaveEditorUiState(
    val isOpen: Boolean = false,
    val stage: SaveEditorStage = SaveEditorStage.SelectUser,
    val isBusy: Boolean = false,
    val progress: String? = null,
    val users: List<String> = emptyList(),
    val selectedUser: String? = null,
    val saveFiles: List<String> = emptyList(),
    val selectedFile: String? = null,
    /** Exactly what was read from disk, for the concurrent-write check. */
    val rawJson: String? = null,
    /**
     * The page's serialization as of the last successful load or save. The page
     * re-serializes the save, so its output never matches [rawJson] byte for
     * byte even with zero edits; this is what "dirty" is measured against.
     */
    val savedBaseline: String? = null,
    /** True once the page has loaded and accepted the staged save. */
    val editorReady: Boolean = false,
    /**
     * Bumped whenever the retained WebView is discarded (a renderer crash) so
     * the composition stops showing the dead view and asks for a fresh one.
     */
    val editorGeneration: Int = 0,
    /**
     * True while the native slot/backup panel covers the editor. The editing
     * stage is otherwise entirely the WebView, so these actions need somewhere
     * to live; the page's repurposed 导出备份 button opens this.
     */
    val toolsOpen: Boolean = false,
    /**
     * Set when the page's 重新读取 / 返回存档列表 button was pressed. The UI owns
     * the unsaved-edit confirmation, so it consumes this and clears it.
     */
    val pendingWebAction: SaveEditorWebAction? = null,
    /** Ten slot summaries read from `user_archive.json`; `null` marks an empty slot. */
    val archiveSlots: List<SaveEditorArchiveSlot?> = List(SaveArchiveIndex.SLOT_COUNT) { null },
    /** Manager-side snapshots of the selected file, newest first. */
    val backups: List<SaveBackupEntry> = emptyList(),
    /** 最近一次读取因游戏侧服务未运行而失败；驱动“启动游戏”入口与自动恢复。 */
    val serviceActivationRequired: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)
