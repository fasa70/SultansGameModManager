package com.sultansgame.modmanager.platform.saveeditor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The JavaScript this app injects into the vendored upstream save editor, plus
 * the pure helper used to decode what comes back.
 *
 * The HTML file under `assets/save-editor/` is vendored byte-for-byte, so every
 * behavior change has to happen at runtime. Three upstream facts this relies on:
 *
 * - `loadData`, `download`, `getCards` and `gcType` are function *declarations*,
 *   therefore also `window` properties. `exportBtn.onclick` resolves `download`
 *   at call time, so replacing `window.download` intercepts every export path.
 * - `saveData` and `fileName` are top-level `let` bindings. They are not
 *   `window` properties, but they live in the shared global lexical scope, so
 *   injected global-scope scripts read and assign them as bare identifiers.
 * - `loadData` swallows its own parse errors and reports through `setMsg`; it
 *   never throws. Success is therefore detected by checking `saveData`.
 */
internal object SaveEditorShim {
    /** Served by `WebViewAssetLoader`, so the page gets a real https origin. */
    const val EDITOR_URL = "https://appassets.androidplatform.net/assets/save-editor/index.html"

    const val BRIDGE_NAME = "SgmmNative"

    /**
     * Installed on every `onPageFinished`. Idempotent, so a duplicate callback
     * cannot double-wrap `window.download`.
     */
    val SHIM_JS = """
        (function () {
            if (window.__sgmmShimInstalled) return "ok";
            window.__sgmmShimInstalled = true;
            // Only the Tailwind classes upstream already uses are available: the
            // stylesheet is a pre-built bundle, so an unused class name is absent.
            function nativeButton(id, label, tone, hook) {
                var button = document.createElement("button");
                button.id = id;
                button.className = tone + " px-4 py-2 rounded-lg transition";
                button.innerText = label;
                button.onclick = function () {
                    try { window.SgmmNative[hook](); } catch (e) {}
                };
                return button;
            }
            var input = document.getElementById("fileInput");
            if (input) {
                input.disabled = true;
                var label = input.closest ? input.closest("label") : input.parentElement;
                if (label && label.style) label.style.display = "none";
            }
            var exportBtn = document.getElementById("exportBtn");
            if (exportBtn) {
                exportBtn.innerText = "\u{1F4BE} 保存到游戏存档";
                exportBtn.onclick = function () {
                    try { window.SgmmNative.onExportRequest(""); } catch (e) {}
                };
            }
            var globalExportBtn = document.getElementById("exportGlobalBtn");
            if (globalExportBtn) {
                globalExportBtn.innerText = "\u{1F4BE} 保存 global.json 到游戏存档";
                globalExportBtn.onclick = function () {
                    try { window.SgmmNative.onGlobalExportRequest(); } catch (e) {}
                };
            }
            var globalBackupBtn = document.getElementById("backupGlobalBtn");
            if (globalBackupBtn) {
                globalBackupBtn.innerText = "\u{1F5C2}\u{FE0F} global.json 槽位 / 备份";
                globalBackupBtn.onclick = function () {
                    try { window.SgmmNative.onToolsRequest(); } catch (e) {}
                };
            }
            var backupBtn = document.getElementById("backupBtn");
            if (backupBtn) {
                backupBtn.innerText = "\u{1F5C2}\u{FE0F} 槽位 / 备份";
                backupBtn.disabled = false;
                backupBtn.onclick = function () {
                    try { window.SgmmNative.onToolsRequest(); } catch (e) {}
                };
            }
            // 重新读取与返回各自独占一个按钮：它们是独立动作，聚合进一个入口
            // 会让人猜不到点下去会发生什么。两者都不随存档是否载入成功而禁用，
            // 载入失败时正是最需要它们的时候。
            var bar = backupBtn ? backupBtn.parentElement : null;
            if (bar) {
                bar.appendChild(nativeButton("sgmmReloadBtn", "\u{1F504} 重新读取", "bg-sky-600", "onReloadRequest"));
                bar.appendChild(nativeButton("sgmmLeaveBtn", "\u{2B05}\u{FE0F} 返回存档列表", "bg-slate-700", "onLeaveRequest"));
            }
            var tip = document.getElementById("tipDbStat");
            var strip = tip && tip.closest ? tip.closest("div") : null;
            if (strip) {
                strip.innerText = "本页直接读写游戏存档：" +
                    "改完点【保存到游戏存档】写回，覆盖前管理器会自动备份。" +
                    "点击卡牌 / 事件标题可展开就地编辑。";
            }
            window.download = function (obj, name) {
                try {
                    var outputName = String(name == null ? "" : name);
                    if (outputName === "global.json" || /_global\\.json$/.test(outputName)) {
                        window.SgmmNative.onGlobalExportRequest();
                    } else {
                        window.SgmmNative.onExportRequest(outputName);
                    }
                } catch (e) {}
            };
            return "ok";
        })()
    """.trimIndent()

    /**
     * Shows a manager message in the page's own status bar. The text is fetched
     * through the bridge rather than interpolated, so no JavaScript escaping is
     * involved on the Kotlin side.
     */
    val SHOW_STATUS_JS = """
        (function () {
            try {
                setMsg(window.SgmmNative.takeStatusMessage());
                return "ok";
            } catch (error) { return "error"; }
        })()
    """.trimIndent()

    /**
     * Hands the staged save text to the page. The text arrives through a
     * `@JavascriptInterface` getter rather than a script literal: a large literal
     * would have to be parsed as JavaScript source and shipped over Chromium
     * IPC, while the getter is a plain one-shot string copy.
     */
    val LOAD_BOOTSTRAP_JS = """
        (function () {
            try {
                var text = window.SgmmNative.takeSaveText();
                if (!text) { window.SgmmNative.onLoadError("存档内容为空"); return "empty"; }
                var name = window.SgmmNative.takeSaveFileName();
                globalData = null;
                globalFileName = "global.json";
                loadData(text, "file");
                if (!saveData) {
                    window.SgmmNative.onLoadError("编辑器无法解析该存档");
                    return "failed";
                }
                fileName = name;
                var globalText = window.SgmmNative.takeGlobalText();
                if (globalText) {
                    var globalName = window.SgmmNative.takeGlobalFileName();
                    loadGlobalData(globalText, globalName || "global.json");
                    if (globalData) window.SgmmNative.onGlobalInjected();
                }
                window.SgmmNative.onSaveInjected();
                return "ok";
            } catch (error) {
                try {
                    window.SgmmNative.onLoadError(String((error && error.message) || error));
                } catch (ignored) {}
                return "error";
            }
        })()
    """.trimIndent()

    /** Loads only global.json, preserving the ordinary save and its edits. */
    val LOAD_GLOBAL_JS = """
        (function () {
            try {
                globalData = null;
                globalFileName = "global.json";
                var text = window.SgmmNative.takeGlobalText();
                if (!text) return "empty";
                var name = window.SgmmNative.takeGlobalFileName();
                loadGlobalData(text, name || "global.json");
                if (!globalData) {
                    window.SgmmNative.onGlobalLoadError("编辑器无法解析 global.json");
                    return "failed";
                }
                window.SgmmNative.onGlobalInjected();
                return "ok";
            } catch (error) {
                try { window.SgmmNative.onGlobalLoadError(String((error && error.message) || error)); } catch (ignored) {}
                return "error";
            }
        })()
    """.trimIndent()

    /**
     * Compact serialization on purpose: upstream's own export path pretty-prints
     * with two-space indent, which roughly doubles the bytes written for no gain
     * — the game never reads the file for its formatting. Returns JS `null` when
     * the page holds nothing usable.
     */
    val PULL_JSON_JS = """
        (function () {
            try {
                if (!saveData) return null;
                return JSON.stringify(saveData);
            } catch (error) { return null; }
        })()
    """.trimIndent()

    val PULL_GLOBAL_JSON_JS = """
        (function () {
            try {
                if (!globalData) return null;
                return JSON.stringify(globalData);
            } catch (error) { return null; }
        })()
    """.trimIndent()

    /**
     * Reproduces the desktop editor's slot summary (upstream
     * `saveArchivePage.save_archive`) using the page's own bundled catalog, so
     * the card name/type source of truth stays in one place. `name` and `path`
     * are filled in on the Kotlin side.
     */
    val ARCHIVE_SUMMARY_JS = """
        (function () {
            try {
                if (!saveData) return null;
                var cards = [];
                try { cards = getCards() || []; } catch (ignored) {}
                if (!cards.length && Array.isArray(saveData.cards)) cards = saveData.cards;
                var sudanCount = 0;
                var maxLife = null;
                for (var i = 0; i < cards.length; i++) {
                    var card = cards[i];
                    if (!card || typeof card !== "object") continue;
                    if (gcType(card.id) !== "sudan") continue;
                    sudanCount++;
                    var life = card.life;
                    if (typeof life === "number" && life === Math.floor(life) && (maxLife === null || life > maxLife)) {
                        maxLife = life;
                    }
                }
                var pool = Array.isArray(saveData.sudan_pool_cards) ? saveData.sudan_pool_cards.length : 0;
                var round = saveData.round;
                return JSON.stringify({
                    live_days: (typeof round === "number" && round === Math.floor(round)) ? round : -1,
                    left_sudan: sudanCount + pool,
                    execution_day: (sudanCount > 0 && maxLife !== null) ? 7 - maxLife : 7,
                    save_time: typeof saveData.saveTime === "string" ? saveData.saveTime : ""
                });
            } catch (error) { return null; }
        })()
    """.trimIndent()

    private val json = Json

    /**
     * Decodes one `evaluateJavascript` result. The callback delivers the JS
     * value JSON-encoded, so a string result arrives quoted and escaped, and
     * JS `null`/`undefined` arrives as the four characters `null`.
     */
    fun decodeJsStringResult(value: String?): String? {
        if (value == null || value == "null") return null
        return runCatching { json.parseToJsonElement(value).jsonPrimitive.contentOrNull }.getOrNull()
    }
}
