package com.sultansgame.modmanager.platform.saveeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract between Kotlin and the injected JavaScript. The WebView
 * itself cannot be exercised in a JVM test, so the decoding helper and the
 * upstream hooks the shim depends on are covered here instead.
 */
class SaveEditorShimTest {
    @Test
    fun decodesQuotedJsonString() {
        assertEquals("""{"a":1}""", SaveEditorShim.decodeJsStringResult("\"{\\\"a\\\":1}\""))
    }

    @Test
    fun decodesEmbeddedEscapes() {
        assertEquals("line\n\"quoted\"\\", SaveEditorShim.decodeJsStringResult("\"line\\n\\\"quoted\\\"\\\\\""))
    }

    @Test
    fun decodesNonAsciiContent() {
        assertEquals("苏丹的游戏", SaveEditorShim.decodeJsStringResult("\"苏丹的游戏\""))
    }

    @Test
    fun treatsJsNullLiteralAsMissing() {
        assertNull(SaveEditorShim.decodeJsStringResult("null"))
    }

    @Test
    fun treatsAbsentCallbackValueAsMissing() {
        assertNull(SaveEditorShim.decodeJsStringResult(null))
    }

    @Test
    fun rejectsMalformedCallbackValue() {
        assertNull(SaveEditorShim.decodeJsStringResult("\"unterminated"))
    }

    @Test
    fun rejectsNonStringCallbackValue() {
        assertNull(SaveEditorShim.decodeJsStringResult("{\"a\":1}"))
    }

    @Test
    fun shimIsIdempotentAndInterceptsEveryExportPath() {
        assertTrue(SaveEditorShim.SHIM_JS.contains("window.__sgmmShimInstalled"))
        // exportBtn and backupBtn both call the global download(), so replacing
        // it is what keeps saves flowing through the native pipeline.
        assertTrue(SaveEditorShim.SHIM_JS.contains("window.download = function"))
        assertTrue(SaveEditorShim.SHIM_JS.contains("onExportRequest"))
        assertTrue(SaveEditorShim.SHIM_JS.contains("getElementById(\"fileInput\")"))
    }

    @Test
    fun shimRepurposesBackupButtonAsTheNativePanelEntry() {
        // The editing stage is entirely WebView, so the page's toolbar is the only
        // way to reach the native actions.
        val shim = SaveEditorShim.SHIM_JS
        assertTrue(shim.contains("getElementById(\"backupBtn\")"))
        assertTrue(shim.contains("onToolsRequest"))
        assertTrue(shim.contains("backupBtn.disabled = false"))
    }

    @Test
    fun shimGivesReloadAndLeaveTheirOwnButtons() {
        // Folding these into the slot/backup entry hid them behind a label that
        // did not say what tapping it would do.
        val shim = SaveEditorShim.SHIM_JS
        assertTrue(shim.contains("sgmmReloadBtn"))
        assertTrue(shim.contains("onReloadRequest"))
        assertTrue(shim.contains("sgmmLeaveBtn"))
        assertTrue(shim.contains("onLeaveRequest"))
    }

    @Test
    fun injectedButtonsOnlyUseStylesUpstreamAlreadyCompiled() {
        // The vendored Tailwind bundle is pre-built, so a class upstream never
        // used has no rule and the button would render unstyled.
        val shim = SaveEditorShim.SHIM_JS
        val used = Regex("""bg-[a-z]+-[0-9]+""").findAll(shim).map { it.value }.toSet()
        assertEquals(setOf("bg-sky-600", "bg-slate-700"), used)
    }

    @Test
    fun statusScriptReportsThroughThePagesOwnBar() {
        // Message text is fetched over the bridge, never interpolated into JS.
        assertTrue(SaveEditorShim.SHOW_STATUS_JS.contains("setMsg(window.SgmmNative.takeStatusMessage())"))
    }

    @Test
    fun bootstrapSetsFileNameAfterLoadDataAndReportsBothOutcomes() {
        val bootstrap = SaveEditorShim.LOAD_BOOTSTRAP_JS
        val load = bootstrap.indexOf("loadData(text")
        val assign = bootstrap.indexOf("fileName = name")
        assertTrue(load >= 0 && assign > load)
        assertTrue(bootstrap.contains("onSaveInjected"))
        assertTrue(bootstrap.contains("onLoadError"))
    }

    @Test
    fun pullUsesCompactSerialization() {
        // Upstream's download() pretty-prints with two-space indent, which roughly
        // doubles the bytes written for no gain.
        assertTrue(SaveEditorShim.PULL_JSON_JS.contains("JSON.stringify(saveData)"))
        assertTrue(!SaveEditorShim.PULL_JSON_JS.contains("null, 2"))
    }

    @Test
    fun archiveSummaryReportsUpstreamSlotFields() {
        val script = SaveEditorShim.ARCHIVE_SUMMARY_JS
        listOf("live_days", "left_sudan", "execution_day", "save_time").forEach {
            assertTrue(it, script.contains(it))
        }
        assertTrue(script.contains("sudan_pool_cards"))
        assertTrue(script.contains("gcType"))
    }

    @Test
    fun editorUrlStaysOnTheAssetLoaderOrigin() {
        assertEquals(
            "https://appassets.androidplatform.net/assets/save-editor/index.html",
            SaveEditorShim.EDITOR_URL,
        )
    }
}
