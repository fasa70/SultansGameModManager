package com.sultansgame.modmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sultansgame.modmanager.ui.ManagerActions
import com.sultansgame.modmanager.ui.ManagerApp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ThemeController

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
                        is ManagerUiEvent.LaunchGameForModService -> startActivity(event.intent)
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
                val state = viewModel.state.collectAsStateWithLifecycle().value
                ManagerApp(
                    state = state,
                    actions = ManagerActions(
                        importMod = { selectModZip.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
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
                        refreshGameMods = viewModel::refreshGameModStorage,
                        launchGame = viewModel::launchGameForModService,
                        setModEnabled = viewModel::setModEnabled,
                        moveMod = viewModel::moveMod,
                        syncMods = viewModel::syncMods,
                        confirmStopGameAndSync = viewModel::confirmStopGameAndSync,
                        dismissStopGameAndSync = viewModel::dismissStopGameAndSyncConfirmation,
                        deleteCachedMod = viewModel::deleteCachedMod,
                        clearModCache = viewModel::clearModCache,
                        acceptNotice = viewModel::acceptLegalNotice,
                        clearFeedback = viewModel::clearFeedback,
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshGameModStorage()
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
