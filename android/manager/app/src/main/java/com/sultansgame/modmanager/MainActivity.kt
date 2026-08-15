package com.sultansgame.modmanager

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sultansgame.modmanager.platform.saf.ExternalZipInbox
import com.sultansgame.modmanager.platform.saf.ExternalZipIntentResult
import com.sultansgame.modmanager.ui.ManagerActions
import com.sultansgame.modmanager.ui.ManagerApp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    private val viewModel by lazy { ViewModelProvider(this)[ManagerViewModel::class.java] }
    private val externalZipInbox by lazy { ExternalZipInbox(this) }
    private var lastExternalIntentSignature: String? = null
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
    private var pendingModExportArtifactId: String? = null
    private val createApksDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        pendingApksExportTransactionId?.let { transactionId -> viewModel.writePreparedApks(transactionId, uri) }
        pendingApksExportTransactionId = null
    }
    private val createModExportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        pendingModExportArtifactId?.let { artifactId -> viewModel.writeModExport(artifactId, uri) }
        pendingModExportArtifactId = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvents.collect { event ->
                    when (event) {
                        is ManagerUiEvent.LaunchGameForModSync -> startActivity(event.intent)
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
                        is ManagerUiEvent.CreateModExportDocument -> {
                            pendingModExportArtifactId = event.artifactId
                            createModExportDocument.launch(event.suggestedName)
                        }
                        is ManagerUiEvent.ShareModExport -> shareModExport(event.artifactId, event.fileName)
                        is ManagerUiEvent.OpenExternalUrl -> {
                            try {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                                        .addCategory(Intent.CATEGORY_BROWSABLE),
                                )
                            } catch (_: android.content.ActivityNotFoundException) {
                                viewModel.onExternalUrlOpenFailed()
                            } catch (_: SecurityException) {
                                viewModel.onExternalUrlOpenFailed()
                            }
                        }
                    }
                }
            }
        }
        setContent {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                val state = viewModel.state.collectAsStateWithLifecycle().value
                ManagerApp(
                    state = state,
                    actions = ManagerActions(
                        importMod = { selectModZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        importLocalApk = { selectLocalApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                        importLocalApkSet = { selectLocalApkSet.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        selectInstalledGame = viewModel::selectInstalledGameSource,
                        preparePatch = viewModel::preparePatchArtifacts,
                        refreshPendingPatch = viewModel::refreshPendingPatchState,
                        requestOriginalUninstall = viewModel::requestOriginalGameUninstall,
                        installPreparedArtifacts = viewModel::installPreparedArtifacts,
                        exportPreparedApks = viewModel::exportPreparedApks,
                        openUnknownSourcesSettings = viewModel::openUnknownSourcesSettings,
                        restartPatch = viewModel::restartPatchFlow,
                        resumePreparedPatch = viewModel::resumePreparedPatch,
                        updatePatchConfirmation = viewModel::updatePatchConfirmation,
                        requestPatchCleanup = viewModel::requestPatchCleanupConfirmation,
                        confirmPatchCleanup = viewModel::confirmPatchCleanup,
                        dismissPatchCleanup = viewModel::dismissPatchCleanupConfirmation,
                        browseWorkshop = viewModel::browseWorkshop,
                        lookupWorkshop = viewModel::lookupWorkshop,
                        beginSteamLogin = viewModel::beginSteamLogin,
                        submitSteamGuard = viewModel::submitSteamGuard,
                        checkPendingSteamLogin = viewModel::checkPendingSteamLogin,
                        logoutSteam = viewModel::logoutSteam,
                        queueWorkshopDownload = viewModel::queueWorkshopDownload,
                        retryWorkshopDownload = viewModel::retryWorkshopDownload,
                        pauseWorkshopDownload = viewModel::pauseWorkshopDownload,
                        resumeWorkshopDownload = viewModel::resumeWorkshopDownload,
                        cancelWorkshopDownload = viewModel::cancelWorkshopDownload,
                        confirmWorkshopImport = viewModel::confirmWorkshopImport,
                        discardWorkshopArtifact = viewModel::discardWorkshopArtifact,
                        removeWorkshopDownload = viewModel::removeWorkshopDownload,
                        refreshGameMods = viewModel::refreshGameModSync,
                        launchGameForModSync = viewModel::launchGameForModSync,
                        setModSyncedToGame = viewModel::setModSyncedToGame,
                        deleteCachedMod = viewModel::deleteCachedMod,
                        renameCachedMod = viewModel::renameCachedMod,
                        clearModCache = viewModel::clearModCache,
                        resetManagerState = viewModel::resetManagerState,
                        openMerge = viewModel::openMerge,
                        closeMerge = viewModel::closeMerge,
                        toggleMergeMod = viewModel::toggleMergeMod,
                        moveMergeMod = viewModel::moveMergeMod,
                        startMerge = viewModel::startMerge,
                        setMergeDisplayName = viewModel::setMergeDisplayName,
                        keepOriginalSync = viewModel::keepOriginalSync,
                        stopOriginalSync = viewModel::stopOriginalSync,
                        openModExport = viewModel::openModExport,
                        closeModExport = viewModel::closeModExport,
                        toggleModExport = viewModel::toggleModExport,
                        setModExportSelection = viewModel::setModExportSelection,
                        selectAllModExport = viewModel::selectAllModExport,
                        requestModExport = viewModel::requestModExport,
                        submitModExport = viewModel::submitModExport,
                        cancelModExportSettings = viewModel::cancelModExportSettings,
                        acceptNotice = viewModel::acceptLegalNotice,
                        setAutoUpdateCheckEnabled = viewModel::setAutoUpdateCheckEnabled,
                        setWorkshopEnabled = viewModel::setWorkshopEnabled,
                        openWorkshopNative = viewModel::openWorkshopNative,
                        dismissAvailableUpdate = viewModel::dismissAvailableUpdate,
                        openAvailableUpdate = viewModel::openAvailableUpdate,
                        clearFeedback = viewModel::clearFeedback,
                        confirmExternalZipImport = viewModel::confirmExternalZipImport,
                        submitZipPassword = viewModel::submitZipPassword,
                        cancelExternalZipImport = viewModel::cancelExternalZipImport,
                    ),
                )
            }
        }
        handleExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    private fun handleExternalIntent(intent: Intent) {
        val signature = "${intent.action}|${intent.data}|${intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)}|${intent.flags}"
        if (signature == lastExternalIntentSignature) return
        lastExternalIntentSignature = signature
        when (val result = externalZipInbox.inspect(intent)) {
            ExternalZipIntentResult.Ignored -> Unit
            ExternalZipIntentResult.MultipleFilesNotSupported -> viewModel.reportExternalImportError("暂不支持一次分享多个 ZIP 文件，请逐个分享。")
            is ExternalZipIntentResult.Accepted -> viewModel.receiveExternalZip(result.uri)
            is ExternalZipIntentResult.Rejected -> viewModel.reportExternalImportError(result.reason)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshGameModSync()
    }

    private fun shareModExport(artifactId: String, fileName: String) {
        val artifact = File(cacheDir, "mod-export/$artifactId.zip")
        if (!artifact.isFile) {
            viewModel.failModExportShare(artifactId, "找不到待分享的 Mod ZIP。")
            return
        }
        val shareFile = File(artifact.parentFile, fileName)
        try {
            if (shareFile.absoluteFile.normalize() != artifact.absoluteFile.normalize() && !artifact.renameTo(shareFile)) {
                error("无法准备分享文件名")
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", shareFile)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Mod ZIP", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "分享到其他应用"))
            viewModel.finishModExportShare(artifactId)
        } catch (_: android.content.ActivityNotFoundException) {
            viewModel.failModExportShare(artifactId, "没有可用的分享应用。")
        } catch (error: Throwable) {
            if (shareFile.absoluteFile.normalize() != artifact.absoluteFile.normalize() && shareFile.isFile) {
                shareFile.renameTo(artifact)
            }
            viewModel.failModExportShare(artifactId, "无法安全分享 Mod ZIP：${error.message ?: "路径无效"}")
        }
    }

    private fun displayNameFor(uri: Uri): String = contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        cursor.takeIf { column >= 0 && it.moveToFirst() }?.getString(column)
    } ?: uri.lastPathSegment ?: "所选文件"
}
