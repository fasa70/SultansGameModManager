package com.sultansgame.modmanager.platform.saveeditor

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/** Signals the page raises on the native side. */
internal sealed interface SaveEditorWebEvent {
    /** The page's own export button was pressed; [fileName] is upstream's name. */
    data class ExportRequested(val fileName: String) : SaveEditorWebEvent

    /** The page's global.json save button was pressed. */
    data object GlobalExportRequested : SaveEditorWebEvent

    /** The repurposed backup button: open the native slot/backup panel. */
    data object ToolsRequested : SaveEditorWebEvent

    /** The injected 重新读取 button. */
    data object ReloadRequested : SaveEditorWebEvent

    /** The injected 返回存档列表 button. */
    data object LeaveRequested : SaveEditorWebEvent

    /** The staged save parsed and rendered. */
    data object SaveInjected : SaveEditorWebEvent

    /** A global.json backup was loaded into the existing page. */
    data object GlobalInjected : SaveEditorWebEvent

    data class LoadFailed(val message: String) : SaveEditorWebEvent

    /** A global.json load failed without invalidating the ordinary save. */
    data class GlobalLoadFailed(val message: String) : SaveEditorWebEvent

    /** The renderer process died; JavaScript state, including edits, is gone. */
    data object RendererGone : SaveEditorWebEvent
}

/**
 * The `window.SgmmNative` object exposed to the page.
 *
 * `@JavascriptInterface` methods run on a WebView-internal binder thread and
 * block the calling JavaScript for as long as the body runs, so every body here
 * is O(1): signals are posted to the main thread, and the staged save/global
 * getters just hand back fields that [stage] filled in beforehand.
 */
internal class SaveEditorNativeHooks(
    private val onEvent: (SaveEditorWebEvent) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var stagedText: String? = null

    @Volatile
    private var stagedFileName: String = ""

    @Volatile
    private var stagedGlobalText: String? = null

    @Volatile
    private var stagedGlobalFileName: String = "global.json"

    @Volatile
    private var statusMessage: String = ""

    /** Makes [text] available to the next [SaveEditorShim.LOAD_BOOTSTRAP_JS] run. */
    fun stage(text: String, fileName: String, globalText: String?, globalFileName: String = "global.json") {
        stagedText = text
        stagedFileName = fileName
        stagedGlobalText = globalText
        stagedGlobalFileName = globalFileName
    }

    /** Makes a replacement global.json available without disturbing the save. */
    fun stageGlobal(text: String, fileName: String = "global.json") {
        stagedGlobalText = text
        stagedGlobalFileName = fileName
    }

    /** Makes [message] available to the next [SaveEditorShim.SHOW_STATUS_JS] run. */
    fun stageStatus(message: String) {
        statusMessage = message
    }

    fun clearStaged() {
        stagedText = null
        stagedFileName = ""
        stagedGlobalText = null
        stagedGlobalFileName = "global.json"
        statusMessage = ""
    }

    @JavascriptInterface
    fun takeSaveText(): String = stagedText.orEmpty()

    @JavascriptInterface
    fun takeSaveFileName(): String = stagedFileName

    @JavascriptInterface
    fun takeGlobalText(): String = stagedGlobalText.orEmpty()

    @JavascriptInterface
    fun takeGlobalFileName(): String = stagedGlobalFileName

    @JavascriptInterface
    fun takeStatusMessage(): String = statusMessage

    @JavascriptInterface
    fun onExportRequest(fileName: String) {
        post(SaveEditorWebEvent.ExportRequested(fileName))
    }

    @JavascriptInterface
    fun onGlobalExportRequest() {
        post(SaveEditorWebEvent.GlobalExportRequested)
    }

    @JavascriptInterface
    fun onToolsRequest() {
        post(SaveEditorWebEvent.ToolsRequested)
    }

    @JavascriptInterface
    fun onReloadRequest() {
        post(SaveEditorWebEvent.ReloadRequested)
    }

    @JavascriptInterface
    fun onLeaveRequest() {
        post(SaveEditorWebEvent.LeaveRequested)
    }

    @JavascriptInterface
    fun onSaveInjected() {
        post(SaveEditorWebEvent.SaveInjected)
    }

    @JavascriptInterface
    fun onLoadError(message: String) {
        post(SaveEditorWebEvent.LoadFailed(message))
    }

    @JavascriptInterface
    fun onGlobalLoadError(message: String) {
        post(SaveEditorWebEvent.GlobalLoadFailed(message))
    }

    @JavascriptInterface
    fun onGlobalInjected() {
        post(SaveEditorWebEvent.GlobalInjected)
    }

    private fun post(event: SaveEditorWebEvent) {
        mainHandler.post { onEvent(event) }
    }
}
