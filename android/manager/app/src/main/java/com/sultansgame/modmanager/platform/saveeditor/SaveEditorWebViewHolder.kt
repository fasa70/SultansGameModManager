package com.sultansgame.modmanager.platform.saveeditor

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * Owns the one retained [WebView] that hosts the vendored HTML save editor.
 *
 * The view outlives the composition on purpose. Switching tabs disposes the
 * screen and rotation recreates the activity, and in both cases a WebView
 * created per composition would take the user's unsaved edits with it — the
 * edits only exist as JavaScript state inside the page. The view is therefore
 * created against a [MutableContextWrapper] over the application context, whose
 * base is swapped to the activity while attached so any window the page needs
 * (a `<select>` popup) still has a window token.
 *
 * Every method here must run on the main thread; the suspend ones switch for you.
 */
internal class SaveEditorWebViewHolder(
    context: Context,
    private val debuggable: Boolean,
    private val onEvent: (SaveEditorWebEvent) -> Unit,
) {
    private val appContext = context.applicationContext
    private val contextWrapper = MutableContextWrapper(appContext)
    private val hooks = SaveEditorNativeHooks(onEvent)
    private val pending = CopyOnWriteArrayList<CancellableContinuation<String?>>()

    private var webView: WebView? = null
    private var destroyed = false
    private var pageReady = false
    private var pendingBootstrap = false

    /**
     * Returns the retained view, creating it on first use. [activityContext] is
     * only borrowed for as long as the view stays attached.
     */
    fun attach(activityContext: Context): WebView {
        check(!destroyed) { "save editor holder already destroyed" }
        contextWrapper.baseContext = activityContext
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        return createWebView().also {
            webView = it
            it.loadUrl(SaveEditorShim.EDITOR_URL)
        }
    }

    /** Detaches the view from its parent and gives back the activity context. */
    fun detach() {
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        contextWrapper.baseContext = appContext
    }

    /**
     * Stages [text] and asks the page to parse it. When the page has not
     * finished loading yet the injection is deferred to `onPageFinished`, which
     * is the normal case: the save is read before the editor is ever attached.
     */
    suspend fun load(
        text: String,
        fileName: String,
        globalText: String? = null,
        globalFileName: String = "global.json",
    ) = withContext(Dispatchers.Main.immediate) {
        if (destroyed) return@withContext
        hooks.stage(text, fileName, globalText, globalFileName)
        val view = webView
        if (view == null || !pageReady) {
            pendingBootstrap = true
            return@withContext
        }
        view.evaluateJavascript(SaveEditorShim.LOAD_BOOTSTRAP_JS, null)
    }

    suspend fun loadGlobal(text: String, fileName: String = "global.json") =
        withContext(Dispatchers.Main.immediate) {
            if (destroyed) return@withContext
            hooks.stageGlobal(text, fileName)
            val view = webView
            if (view == null || !pageReady) return@withContext
            view.evaluateJavascript(SaveEditorShim.LOAD_GLOBAL_JS, null)
        }

    /**
     * The page's current save as compact JSON, or `null` when the page is gone
     * or holds nothing serializable.
     */
    suspend fun pullCurrentJson(): String? =
        SaveEditorShim.decodeJsStringResult(evaluate(SaveEditorShim.PULL_JSON_JS))

    /** The page's current global.json as compact JSON, or null when unavailable. */
    suspend fun pullCurrentGlobalJson(): String? =
        SaveEditorShim.decodeJsStringResult(evaluate(SaveEditorShim.PULL_GLOBAL_JSON_JS))

    /** Reports a manager-side outcome in the page's own status bar. */
    suspend fun showStatus(message: String) = withContext(Dispatchers.Main.immediate) {
        val view = webView
        if (destroyed || view == null || !pageReady) return@withContext
        hooks.stageStatus(message)
        view.evaluateJavascript(SaveEditorShim.SHOW_STATUS_JS, null)
    }

    /** The slot summary computed by the page, as a JSON object string. */
    suspend fun pullArchiveSummary(): String? =
        SaveEditorShim.decodeJsStringResult(evaluate(SaveEditorShim.ARCHIVE_SUMMARY_JS))

    /** Reloads the page, deliberately discarding all JavaScript state. */
    suspend fun reset() = withContext(Dispatchers.Main.immediate) {
        hooks.clearStaged()
        pendingBootstrap = false
        pageReady = false
        webView?.loadUrl(SaveEditorShim.EDITOR_URL)
        Unit
    }

    /**
     * Drops the current view and page state but keeps the holder usable; the
     * next [attach] builds a fresh WebView. Backup files on disk are untouched.
     */
    fun recycle() {
        hooks.clearStaged()
        discardWebView()
    }

    /** Permanent teardown. Safe to call more than once. */
    fun destroy() {
        destroyed = true
        hooks.clearStaged()
        discardWebView()
    }

    private suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main.immediate) {
        val view = webView
        if (destroyed || view == null || !pageReady) return@withContext null
        suspendCancellableCoroutine { continuation ->
            pending += continuation
            continuation.invokeOnCancellation { pending.remove(continuation) }
            view.evaluateJavascript(script) { value ->
                if (pending.remove(continuation) && continuation.isActive) continuation.resume(value)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .build()
        return WebView(contextWrapper).apply {
            // The page is self-contained: inlined CSS/JS, bundled game data, no
            // network use at all. Only scripting and localStorage are needed.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(hooks, SaveEditorShim.BRIDGE_NAME)
            webViewClient = editorClient(assetLoader)
        }
    }

    private fun editorClient(assetLoader: WebViewAssetLoader) = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

        /** Fail closed: the editor needs no navigation, so allow none. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.url.toString() != SaveEditorShim.EDITOR_URL

        override fun onPageFinished(view: WebView, url: String) {
            if (url != SaveEditorShim.EDITOR_URL) return
            view.evaluateJavascript(SaveEditorShim.SHIM_JS) {
                pageReady = true
                if (pendingBootstrap) {
                    pendingBootstrap = false
                    view.evaluateJavascript(SaveEditorShim.LOAD_BOOTSTRAP_JS, null)
                }
            }
        }

        /**
         * A crashed WebView must never be reused, so drop it and report up.
         * The framework only calls this on API 26+; older devices kill the whole
         * app process instead, which there is nothing to recover from.
         */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            discardWebView()
            onEvent(SaveEditorWebEvent.RendererGone)
            return true
        }
    }

    private fun discardWebView() {
        pageReady = false
        pendingBootstrap = false
        val view = webView
        webView = null
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeJavascriptInterface(SaveEditorShim.BRIDGE_NAME)
            view.stopLoading()
            view.destroy()
        }
        // Callbacks for a destroyed WebView never fire; resume so no caller hangs.
        val waiting = pending.toList()
        pending.clear()
        waiting.forEach { if (it.isActive) it.resume(null) }
    }
}
