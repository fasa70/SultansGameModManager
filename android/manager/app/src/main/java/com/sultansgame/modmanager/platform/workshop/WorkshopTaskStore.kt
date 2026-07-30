package com.sultansgame.modmanager.platform.workshop

import android.content.Context
import androidx.room.Room
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.model.SULTANS_GAME_APP_ID
import com.sultansgame.modmanager.model.WorkshopAccessMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable Room-backed task repository. Every mutation is a conditional database
 * transition: UI snapshots and superseded workers cannot overwrite user intent.
 */
class WorkshopTaskStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val database = database(applicationContext)
    private val dao = database.taskDao()
    private val initialization = CompletableDeferred<Unit>()
    private val mutableReady = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = mutableTasks
    val ready: StateFlow<Boolean> = mutableReady

    init {
        scope.launch {
            val initialTasks = runCatching {
                migrateLegacyTasksIfNeeded()
                dao.getAll().mapNotNull(WorkshopDownloadTaskEntity::toModel)
            }.getOrDefault(emptyList())
            mutableTasks.value = initialTasks
            mutableReady.value = true
            initialization.complete(Unit)
            dao.observeAll().collect { entities ->
                mutableTasks.value = entities.mapNotNull(WorkshopDownloadTaskEntity::toModel)
            }
        }
    }

    fun get(id: String): DownloadTask? = mutableTasks.value.firstOrNull { it.id == id }

    suspend fun getPersisted(id: String): DownloadTask? {
        initialization.await()
        return dao.get(id)?.toModel()
    }

    suspend fun create(task: DownloadTask): Boolean {
        initialization.await()
        return runCatching {
            dao.insert(task.copy(attemptGeneration = task.attemptGeneration.coerceAtLeast(1L)).toEntity())
            true
        }.getOrDefault(false)
    }

    suspend fun requestPause(id: String): Boolean {
        initialization.await()
        return dao.requestPause(id, now()) == 1
    }

    suspend fun requestCancel(id: String): Boolean {
        initialization.await()
        return dao.requestCancel(id, now()) == 1
    }

    suspend fun requestRetry(id: String): DownloadTask? {
        initialization.await()
        if (dao.requestRetry(id, now()) != 1) return null
        return dao.get(id)?.toModel()
    }

    /** Atomically removes a non-importing task and returns its staging cleanup snapshot. */
    suspend fun takeForDeletion(id: String): DownloadTask? {
        initialization.await()
        return dao.takeAndRemoveUnlessImporting(id)?.toModel()
    }

    suspend fun updateActiveState(
        id: String,
        attemptGeneration: Long,
        stage: DownloadStage,
        failure: DownloadFailureCode? = null,
    ): Boolean {
        initialization.await()
        return dao.updateActiveState(
            id = id,
            attemptGeneration = attemptGeneration,
            stage = stage.name,
            failure = failure?.name,
            updatedAtEpochMillis = now(),
        ) == 1
    }

    suspend fun updateActiveProgress(
        id: String,
        attemptGeneration: Long,
        stage: DownloadStage,
        downloadedBytes: Long,
        totalBytes: Long?,
    ): Boolean {
        initialization.await()
        return dao.updateActiveProgress(
            id = id,
            attemptGeneration = attemptGeneration,
            stage = stage.name,
            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
            totalBytes = totalBytes?.takeIf { it >= 0L },
            updatedAtEpochMillis = now(),
        ) == 1
    }

    suspend fun completeDownload(
        id: String,
        attemptGeneration: Long,
        rawArtifactDigestSha256: String,
        completedFileCount: Int,
    ): Boolean {
        initialization.await()
        return dao.completeDownload(
            id = id,
            attemptGeneration = attemptGeneration,
            rawArtifactDigestSha256 = rawArtifactDigestSha256,
            completedFileCount = completedFileCount.coerceAtLeast(0),
            updatedAtEpochMillis = now(),
        ) == 1
    }

    suspend fun invalidateArtifact(id: String): Boolean {
        initialization.await()
        return dao.invalidateArtifact(id, now()) == 1
    }

    suspend fun beginImport(id: String): DownloadTask? {
        initialization.await()
        if (dao.beginImport(id, now()) != 1) return null
        return dao.get(id)?.toModel()
    }

    suspend fun finishImport(id: String): Boolean {
        initialization.await()
        return dao.finishImport(id, now()) == 1
    }

    suspend fun failImport(id: String): Boolean {
        initialization.await()
        return dao.failImport(id, DownloadFailureCode.ImportFailed.name, now()) == 1
    }

    /** Resets only safely resumable task states after process death. */
    suspend fun recoverInterruptedTasks(): List<DownloadTask> {
        initialization.await()
        dao.requeueInterruptedDownloads(now())
        dao.invalidateUnverifiableImports(now())
        dao.restoreInterruptedImports(now())
        return dao.getAll().mapNotNull(WorkshopDownloadTaskEntity::toModel)
    }

    private suspend fun migrateLegacyTasksIfNeeded() {
        val preferences = applicationContext.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean(LEGACY_MIGRATION_COMPLETE_KEY, false)) return

        val migrated = runCatching {
            val raw = preferences.getString(LEGACY_TASKS_KEY, "[]") ?: "[]"
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    parseLegacyTask(array.getJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

        for (task in migrated) {
            dao.upsertForMigration(task.toEntity())
        }
        preferences.edit()
            .remove(LEGACY_TASKS_KEY)
            .putBoolean(LEGACY_MIGRATION_COMPLETE_KEY, true)
            .apply()
    }

    private fun parseLegacyTask(json: JSONObject): DownloadTask? {
        val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
        val publishedFileId = PublishedFileId.parse(json.optString("publishedFileId")) ?: return null
        val stage = enumValueOrNull<DownloadStage>(json.optString("stage")) ?: return null
        val accessMode = enumValueOrNull<WorkshopAccessMode>(json.optString("accessMode")) ?: WorkshopAccessMode.Anonymous
        val isVerifiedAwaitingImport = stage == DownloadStage.AwaitingImportConfirmation &&
            json.optString("digest").matches(Regex("[0-9a-fA-F]{64}")) &&
            json.optInt("completedFileCount", 0) > 0 &&
            runCatching { java.util.UUID.fromString(id) }.isSuccess
        val safeStage = when {
            isVerifiedAwaitingImport -> DownloadStage.AwaitingImportConfirmation
            stage in setOf(DownloadStage.Importing, DownloadStage.Imported) -> DownloadStage.Failed
            stage in ACTIVE_STAGES -> DownloadStage.Queued
            else -> stage
        }
        val safelyPreservedArtifact = safeStage == DownloadStage.AwaitingImportConfirmation
        return DownloadTask(
            id = id,
            appId = SULTANS_GAME_APP_ID,
            publishedFileId = publishedFileId,
            accessMode = accessMode,
            stage = safeStage,
            title = json.optString("title"),
            downloadedBytes = if (safelyPreservedArtifact) json.optLong("downloadedBytes", 0L).coerceAtLeast(0L) else 0L,
            totalBytes = json.optLong("totalBytes", -1L).takeIf { it >= 0L },
            failure = if (safelyPreservedArtifact) enumValueOrNull<DownloadFailureCode>(json.optString("failure"))
            else DownloadFailureCode.InvalidArtifact,
            rawArtifactDigestSha256 = if (safelyPreservedArtifact) json.optString("digest") else null,
            completedFileCount = if (safelyPreservedArtifact) json.optInt("completedFileCount", 0) else 0,
            pauseRequested = json.optBoolean("pauseRequested", false),
            attemptGeneration = 1L,
            createdAtEpochMillis = json.optLong("createdAt", now()),
            updatedAtEpochMillis = now(),
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private val ACTIVE_STAGES = setOf(
            DownloadStage.Queued,
            DownloadStage.ResolvingMetadata,
            DownloadStage.AwaitingPublicUrl,
            DownloadStage.Downloading,
            DownloadStage.Verifying,
        )
        private const val DATABASE_NAME = "workshop-downloads.db"
        private const val LEGACY_PREFERENCES_NAME = "workshop-download-queue"
        private const val LEGACY_TASKS_KEY = "tasks-v1"
        private const val LEGACY_MIGRATION_COMPLETE_KEY = "room-migration-v1-complete"

        @Volatile
        private var sharedDatabase: WorkshopDownloadDatabase? = null

        private fun database(context: Context): WorkshopDownloadDatabase = sharedDatabase ?: synchronized(this) {
            sharedDatabase ?: Room.databaseBuilder(
                context.applicationContext,
                WorkshopDownloadDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(WorkshopDownloadDatabase.MIGRATION_1_2)
                .build().also { sharedDatabase = it }
        }
    }
}
