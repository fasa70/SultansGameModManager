package com.sultansgame.modmanager.platform.workshop

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.SULTANS_GAME_APP_ID
import com.sultansgame.modmanager.platform.auth.SteamCmAuthProvider
import kotlinx.coroutines.flow.collect
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.workshop.DownloadEvent
import top.apricityx.workshop.workshop.WorkshopDownloadEngine
import top.apricityx.workshop.workshop.WorkshopDownloadRequest
import java.io.File
import java.security.MessageDigest

class WorkshopDownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val store = WorkshopTaskStore(applicationContext)
        val task = store.get(taskId) ?: return Result.success()
        if (task.appId != SULTANS_GAME_APP_ID || task.stage in terminalStages) return Result.success()

        val auth = SteamCmAuthProvider(applicationContext)
        val account = auth.activeSession()
        if (task.accessMode == com.sultansgame.modmanager.model.WorkshopAccessMode.Account && account == null) {
            store.update(taskId) { it.copy(stage = DownloadStage.NeedsLogin, failure = DownloadFailureCode.LoginRequired) }
            return Result.failure()
        }

        val root = File(applicationContext.filesDir, "workshop-staging/$taskId")
        if (!root.mkdirs() && !root.isDirectory) {
            store.update(taskId) { it.copy(stage = DownloadStage.Failed, failure = DownloadFailureCode.Network) }
            return Result.retry()
        }

        store.update(taskId) { it.copy(stage = DownloadStage.ResolvingMetadata, failure = null) }
        val engine = createEngine(account)
        var failed = false
        engine.download(
            WorkshopDownloadRequest(
                appId = SULTANS_GAME_APP_ID,
                publishedFileId = task.publishedFileId.value,
                outputDir = root,
            ),
        ).collect { event ->
            when (event) {
                is DownloadEvent.StateChanged -> store.update(taskId) { current ->
                    current.copy(stage = when (event.state) {
                        top.apricityx.workshop.workshop.DownloadState.Resolving -> DownloadStage.ResolvingMetadata
                        top.apricityx.workshop.workshop.DownloadState.Connecting,
                        top.apricityx.workshop.workshop.DownloadState.Downloading -> DownloadStage.Downloading
                        top.apricityx.workshop.workshop.DownloadState.Paused -> DownloadStage.Paused
                        top.apricityx.workshop.workshop.DownloadState.Success -> DownloadStage.Verifying
                        top.apricityx.workshop.workshop.DownloadState.Failed -> DownloadStage.Failed
                        top.apricityx.workshop.workshop.DownloadState.Idle -> current.stage
                    })
                }
                is DownloadEvent.Progress -> store.update(taskId) { current ->
                    current.copy(downloadedBytes = event.writtenBytes, totalBytes = event.totalBytes ?: current.totalBytes)
                }
                is DownloadEvent.Completed -> {
                    val digest = directoryDigest(root)
                    store.update(taskId) { current ->
                        current.copy(
                            stage = DownloadStage.AwaitingImportConfirmation,
                            rawArtifactDigestSha256 = digest,
                            completedFileCount = event.files.size,
                        )
                    }
                }
                is DownloadEvent.Failed -> {
                    failed = true
                    store.update(taskId) { it.copy(stage = DownloadStage.Failed, failure = DownloadFailureCode.Network) }
                }
                is DownloadEvent.FileCompleted,
                is DownloadEvent.LogAppended -> Unit
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    private fun createEngine(account: top.apricityx.workshop.steam.protocol.SteamAccountSession?): WorkshopDownloadEngine =
        WorkshopDownloadEngine.createDefault(
            sessionFactory = { OkHttpSteamCmSession() },
            sessionConnector = { session, servers ->
                account?.let { session.connectWithRefreshToken(servers, it) } ?: session.connectAnonymous(servers)
            },
        )

    private fun directoryDigest(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).path }.forEach { file ->
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray())
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_TASK_ID = "task-id"
        private val terminalStages = setOf(DownloadStage.Imported, DownloadStage.Cancelled)
    }
}
