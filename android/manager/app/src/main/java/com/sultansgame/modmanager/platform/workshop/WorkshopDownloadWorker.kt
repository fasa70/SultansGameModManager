package com.sultansgame.modmanager.platform.workshop

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.SULTANS_GAME_APP_ID
import com.sultansgame.modmanager.platform.auth.SteamCmAuthProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
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
        val task = store.getPersisted(taskId) ?: return Result.success()
        if (task.pauseRequested) {
            store.forceState(taskId, DownloadStage.Paused, pauseRequested = true)
            return Result.success()
        }
        if (task.appId != SULTANS_GAME_APP_ID || task.stage in terminalStages) return Result.success()

        val auth = SteamCmAuthProvider(applicationContext)
        val account = auth.activeSession()
        if (task.accessMode == com.sultansgame.modmanager.model.WorkshopAccessMode.Account && account == null) {
            store.forceState(taskId, DownloadStage.NeedsLogin, failure = DownloadFailureCode.LoginRequired)
            return Result.failure()
        }
        if (
            task.accessMode == com.sultansgame.modmanager.model.WorkshopAccessMode.Account &&
            task.boundAccountHash != null &&
            task.boundAccountHash != accountHash(account!!)
        ) {
            store.forceState(taskId, DownloadStage.NeedsLogin, failure = DownloadFailureCode.LoginRequired)
            return Result.failure()
        }

        val root = File(applicationContext.filesDir, "workshop-staging/$taskId")
        if (!root.mkdirs() && !root.isDirectory) {
            store.forceState(taskId, DownloadStage.Failed, failure = DownloadFailureCode.Network)
            return Result.retry()
        }

        store.updateActiveState(taskId, DownloadStage.ResolvingMetadata)
        val engine = createEngine(account)
        var failed = false
        var paused = false
        engine.download(
            WorkshopDownloadRequest(
                appId = SULTANS_GAME_APP_ID,
                publishedFileId = task.publishedFileId.value,
                outputDir = root,
            ),
        ).takeWhile {
            val shouldPause = store.getPersisted(taskId)?.pauseRequested == true
            if (shouldPause) {
                paused = true
                store.forceState(taskId, DownloadStage.Paused, pauseRequested = true)
            }
            !shouldPause
        }.collect { event ->
            when (event) {
                is DownloadEvent.StateChanged -> {
                    val stage = when (event.state) {
                        top.apricityx.workshop.workshop.DownloadState.Resolving -> DownloadStage.ResolvingMetadata
                        top.apricityx.workshop.workshop.DownloadState.Connecting,
                        top.apricityx.workshop.workshop.DownloadState.Downloading -> DownloadStage.Downloading
                        top.apricityx.workshop.workshop.DownloadState.Paused -> DownloadStage.Paused
                        top.apricityx.workshop.workshop.DownloadState.Success -> DownloadStage.Verifying
                        top.apricityx.workshop.workshop.DownloadState.Failed -> DownloadStage.Failed
                        top.apricityx.workshop.workshop.DownloadState.Idle -> null
                    }
                    if (stage != null) store.updateActiveState(taskId, stage)
                }
                is DownloadEvent.Progress -> store.updateActiveProgress(
                    id = taskId,
                    stage = DownloadStage.Downloading,
                    downloadedBytes = event.writtenBytes,
                    totalBytes = event.totalBytes,
                )
                is DownloadEvent.Completed -> {
                    val digest = directoryDigest(root)
                    store.completeDownload(taskId, digest, event.files.size)
                }
                is DownloadEvent.Failed -> {
                    failed = true
                    store.updateActiveState(taskId, DownloadStage.Failed, failureFor(event.message))
                }
                is DownloadEvent.FileCompleted,
                is DownloadEvent.LogAppended -> Unit
            }
        }
        return when {
            paused -> Result.success()
            failed -> Result.failure()
            else -> Result.success()
        }
    }

    private fun createEngine(account: top.apricityx.workshop.steam.protocol.SteamAccountSession?): WorkshopDownloadEngine =
        WorkshopDownloadEngine.createDefault(
            sessionFactory = { OkHttpSteamCmSession() },
            sessionConnector = { session, servers ->
                account?.let { session.connectWithRefreshToken(servers, it) } ?: session.connectAnonymous(servers)
            },
        )

    private fun accountHash(account: top.apricityx.workshop.steam.protocol.SteamAccountSession): String =
        MessageDigest.getInstance("SHA-256")
            .digest(account.steamId.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun failureFor(message: String): DownloadFailureCode = when {
        message.contains("consumer app", ignoreCase = true) ||
            message.contains("result=", ignoreCase = true) ||
            message.contains("metadata", ignoreCase = true) -> DownloadFailureCode.MetadataUnavailable
        message.contains("file_url", ignoreCase = true) ||
            message.contains("manifest", ignoreCase = true) ||
            message.contains("unavailable", ignoreCase = true) -> DownloadFailureCode.NotOwnedOrUnavailable
        message.contains("size", ignoreCase = true) -> DownloadFailureCode.SizeMismatch
        message.contains("checksum", ignoreCase = true) ||
            message.contains("integrity", ignoreCase = true) -> DownloadFailureCode.ChecksumMismatch
        message.contains("http", ignoreCase = true) -> DownloadFailureCode.HttpFailure
        else -> DownloadFailureCode.Network
    }

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
