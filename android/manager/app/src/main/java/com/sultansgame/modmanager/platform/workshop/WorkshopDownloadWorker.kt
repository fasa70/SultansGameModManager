package com.sultansgame.modmanager.platform.workshop

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.SULTANS_GAME_APP_ID
import com.sultansgame.modmanager.platform.auth.SteamCmAuthProvider
import com.sultansgame.modmanager.platform.auth.steamAccountBindingHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.workshop.DownloadEvent
import top.apricityx.workshop.workshop.WorkshopDownloadEngine
import top.apricityx.workshop.workshop.WorkshopDownloadRequest
import java.io.File

class WorkshopDownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val attemptGeneration = inputData.getLong(KEY_ATTEMPT_GENERATION, INVALID_ATTEMPT_GENERATION)
        if (attemptGeneration < 1L) return Result.failure()

        val store = WorkshopTaskStore(applicationContext)
        return try {
            runDownload(store, taskId, attemptGeneration)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            store.updateActiveState(
                id = taskId,
                attemptGeneration = attemptGeneration,
                stage = DownloadStage.Failed,
                failure = DownloadFailureCode.Network,
            )
            Result.failure()
        }
    }

    private suspend fun runDownload(
        store: WorkshopTaskStore,
        taskId: String,
        attemptGeneration: Long,
    ): Result {
        val task = store.getPersisted(taskId) ?: return Result.success()
        if (
            task.attemptGeneration != attemptGeneration ||
            task.appId != SULTANS_GAME_APP_ID ||
            task.stage !in RUNNABLE_STAGES ||
            task.pauseRequested
        ) {
            return Result.success()
        }

        val auth = SteamCmAuthProvider(applicationContext)
        val account = auth.persistentSession()
        if (task.accessMode == com.sultansgame.modmanager.model.WorkshopAccessMode.Account && account == null) {
            store.updateActiveState(
                taskId,
                attemptGeneration,
                DownloadStage.NeedsLogin,
                DownloadFailureCode.LoginRequired,
            )
            return Result.failure()
        }
        if (
            task.accessMode == com.sultansgame.modmanager.model.WorkshopAccessMode.Account &&
            task.boundAccountHash != null &&
            task.boundAccountHash != steamAccountBindingHash(account!!.steamId)
        ) {
            store.updateActiveState(
                taskId,
                attemptGeneration,
                DownloadStage.NeedsLogin,
                DownloadFailureCode.LoginRequired,
            )
            return Result.failure()
        }

        val root = File(applicationContext.filesDir, "workshop-staging/$taskId")
        if (!root.mkdirs() && !root.isDirectory) {
            store.updateActiveState(
                taskId,
                attemptGeneration,
                DownloadStage.Failed,
                DownloadFailureCode.Network,
            )
            return Result.failure()
        }

        if (!store.updateActiveState(taskId, attemptGeneration, DownloadStage.ResolvingMetadata)) {
            return Result.success()
        }

        val engine = createEngine(account)
        var failed = false
        engine.download(
            WorkshopDownloadRequest(
                appId = SULTANS_GAME_APP_ID,
                publishedFileId = task.publishedFileId.value,
                outputDir = root,
            ),
        ).takeWhile {
            store.getPersisted(taskId)?.let {
                it.attemptGeneration == attemptGeneration &&
                    it.stage in RUNNABLE_STAGES &&
                    !it.pauseRequested
            } == true
        }.collect { event ->
            when (event) {
                is DownloadEvent.StateChanged -> {
                    val stage = when (event.state) {
                        top.apricityx.workshop.workshop.DownloadState.Resolving -> DownloadStage.ResolvingMetadata
                        top.apricityx.workshop.workshop.DownloadState.Connecting,
                        top.apricityx.workshop.workshop.DownloadState.Downloading -> DownloadStage.Downloading
                        top.apricityx.workshop.workshop.DownloadState.Paused -> null
                        top.apricityx.workshop.workshop.DownloadState.Success -> DownloadStage.Verifying
                        top.apricityx.workshop.workshop.DownloadState.Failed -> null
                        top.apricityx.workshop.workshop.DownloadState.Idle -> null
                    }
                    if (stage != null) {
                        val updated = store.updateActiveState(taskId, attemptGeneration, stage)
                        if (!updated) return@collect
                    }
                }
                is DownloadEvent.Progress -> {
                    store.updateActiveProgress(
                        id = taskId,
                        attemptGeneration = attemptGeneration,
                        stage = DownloadStage.Downloading,
                        downloadedBytes = event.writtenBytes,
                        totalBytes = event.totalBytes,
                    )
                }
                is DownloadEvent.Completed -> {
                    val summary = WorkshopStagingArtifact.summarize(root)
                    store.completeDownload(
                        taskId,
                        attemptGeneration,
                        summary.sha256,
                        summary.fileCount,
                    )
                }
                is DownloadEvent.Failed -> {
                    failed = true
                    store.updateActiveState(
                        taskId,
                        attemptGeneration,
                        DownloadStage.Failed,
                        failureFor(event.failure),
                    )
                }
                is DownloadEvent.FileCompleted,
                is DownloadEvent.LogAppended -> Unit
            }
        }
        return if (failed) Result.failure() else Result.success()
    }

    private fun createEngine(account: top.apricityx.workshop.steam.protocol.SteamAccountSession?): WorkshopDownloadEngine =
        WorkshopDownloadEngine.createDefault(
            sessionFactory = { OkHttpSteamCmSession() },
            sessionConnector = { session, servers ->
                account?.let { session.connectWithRefreshToken(servers, it) } ?: session.connectAnonymous(servers)
            },
        )

    private fun failureFor(failure: top.apricityx.workshop.workshop.DownloadFailure): DownloadFailureCode = when (failure) {
        top.apricityx.workshop.workshop.DownloadFailure.MetadataUnavailable -> DownloadFailureCode.MetadataUnavailable
        top.apricityx.workshop.workshop.DownloadFailure.NotOwnedOrUnavailable -> DownloadFailureCode.NotOwnedOrUnavailable
        top.apricityx.workshop.workshop.DownloadFailure.UnsafeOrInvalidContent -> DownloadFailureCode.InvalidArtifact
        top.apricityx.workshop.workshop.DownloadFailure.ResponseTooLarge -> DownloadFailureCode.ResponseTooLarge
        top.apricityx.workshop.workshop.DownloadFailure.SizeMismatch -> DownloadFailureCode.SizeMismatch
        top.apricityx.workshop.workshop.DownloadFailure.ChecksumMismatch -> DownloadFailureCode.ChecksumMismatch
        top.apricityx.workshop.workshop.DownloadFailure.InsufficientStorage -> DownloadFailureCode.InsufficientStorage
        is top.apricityx.workshop.workshop.DownloadFailure.HttpFailure -> DownloadFailureCode.HttpFailure
        top.apricityx.workshop.workshop.DownloadFailure.Network -> DownloadFailureCode.Network
    }

    companion object {
        const val KEY_TASK_ID = "task-id"
        const val KEY_ATTEMPT_GENERATION = "attempt-generation"
        private const val INVALID_ATTEMPT_GENERATION = -1L
        private val RUNNABLE_STAGES = setOf(
            DownloadStage.Queued,
            DownloadStage.ResolvingMetadata,
            DownloadStage.AwaitingPublicUrl,
            DownloadStage.Downloading,
            DownloadStage.Verifying,
        )
    }
}
