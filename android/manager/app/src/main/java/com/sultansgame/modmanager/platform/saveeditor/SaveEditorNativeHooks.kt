package com.sultansgame.modmanager.platform.saveeditor

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/** Signals the page raises on the native side. */
internal sealed interface SaveEditorWebEvent {
    /** The page's own export button was pressed; [fileName] is upstream's name. */
    data class ExportRequested(val fileName: String) : SaveEditorWebEvent

    /** The staged save parsed and rendered. */
    data object SaveInjected : SaveEditorWebEvent

    data class LoadFailed(val message: String) : SaveEditorWebEvent

    /** The renderer process died; JavaScript state, including edits, is gone. */
    data object RendererGone : SaveEditorWebEvent
}

/**
 * The `window.SgmmNative` object exposed to the page.
 *
 * `@JavascriptInterface` methods run on a WebView-internal binder thread and
 * block the calling JavaScript for as long as the body runs, so every body here
 * is O(1): signals are posted to the main thread, and the two save getters just
 * hand back a field that [stage] filled in beforehand.
 */
internal class SaveEditorNativeHooks(
    private val onEvent: (SaveEditorWebEvent) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var stagedText: String? = null

    @Volatile
    private var stagedFileName: String = ""

    /** Makes [text] available to the next [SaveEditorShim.LOAD_BOOTSTRAP_JS] run. */
    fun stage(text: String, fileName: String) {
        stagedText = text
        stagedFileName = fileName
    }

    fun clearStaged() {
        stagedText = null
        stagedFileName = ""
    }

    @JavascriptInterface
    fun takeSaveText(): String = stagedText.orEmpty()

    @JavascriptInterface
    fun takeSaveFileName(): String = stagedFileName

    @JavascriptInterface
    fun onExportRequest(fileName: String) {
        post(SaveEditorWebEvent.ExportRequested(fileName))
    }

    @JavascriptInterface
    fun onSaveInjected() {
        post(SaveEditorWebEvent.SaveInjected)
    }

    @JavascriptInterface
    fun onLoadError(message: String) {
        post(SaveEditorWebEvent.LoadFailed(message))
    }

    private fun post(event: SaveEditorWebEvent) {
        mainHandler.post { onEvent(event) }
    }
}
