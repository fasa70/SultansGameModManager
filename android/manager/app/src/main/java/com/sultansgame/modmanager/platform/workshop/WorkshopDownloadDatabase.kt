package com.sultansgame.modmanager.platform.workshop

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.model.SULTANS_GAME_APP_ID
import com.sultansgame.modmanager.model.WorkshopAccessMode
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "workshop_download_tasks",
    indices = [Index(value = ["updatedAtEpochMillis"])],
)
data class WorkshopDownloadTaskEntity(
    @PrimaryKey val id: String,
    val appId: Long,
    val publishedFileId: String,
    val accessMode: String,
    val boundAccountHash: String?,
    val stage: String,
    val title: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val failure: String?,
    val rawArtifactDigestSha256: String?,
    val completedFileCount: Int,
    val pauseRequested: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Dao
interface WorkshopDownloadTaskDao {
    @Query("SELECT * FROM workshop_download_tasks ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<WorkshopDownloadTaskEntity>>

    @Query("SELECT * FROM workshop_download_tasks ORDER BY createdAtEpochMillis DESC")
    suspend fun getAll(): List<WorkshopDownloadTaskEntity>

    @Query("SELECT * FROM workshop_download_tasks WHERE id = :id LIMIT 1")
    suspend fun get(id: String): WorkshopDownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: WorkshopDownloadTaskEntity)

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = :stage,
            failure = :failure,
            pauseRequested = :pauseRequested,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
    """)
    suspend fun forceState(
        id: String,
        stage: String,
        failure: String?,
        pauseRequested: Boolean,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = :stage,
            failure = :failure,
            pauseRequested = 0,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
          AND pauseRequested = 0
          AND stage NOT IN ('Paused', 'Cancelled', 'Imported', 'AwaitingImportConfirmation')
    """)
    suspend fun updateActiveState(
        id: String,
        stage: String,
        failure: String?,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = :stage,
            downloadedBytes = :downloadedBytes,
            totalBytes = :totalBytes,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
          AND pauseRequested = 0
          AND stage NOT IN ('Paused', 'Cancelled', 'Imported', 'AwaitingImportConfirmation')
    """)
    suspend fun updateActiveProgress(
        id: String,
        stage: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = 'AwaitingImportConfirmation',
            failure = NULL,
            pauseRequested = 0,
            rawArtifactDigestSha256 = :rawArtifactDigestSha256,
            completedFileCount = :completedFileCount,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
          AND pauseRequested = 0
          AND stage NOT IN ('Paused', 'Cancelled', 'Imported')
    """)
    suspend fun completeDownload(
        id: String,
        rawArtifactDigestSha256: String,
        completedFileCount: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM workshop_download_tasks WHERE id = :id")
    suspend fun remove(id: String)
}

@Database(
    entities = [WorkshopDownloadTaskEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class WorkshopDownloadDatabase : RoomDatabase() {
    abstract fun taskDao(): WorkshopDownloadTaskDao
}

internal fun WorkshopDownloadTaskEntity.toModel(): DownloadTask? {
    val parsedId = PublishedFileId.parse(publishedFileId) ?: return null
    val parsedStage = enumValueOrNull<DownloadStage>(stage) ?: return null
    val parsedAccessMode = enumValueOrNull<WorkshopAccessMode>(accessMode) ?: WorkshopAccessMode.Anonymous
    return DownloadTask(
        id = id,
        appId = appId.toUInt(),
        publishedFileId = parsedId,
        accessMode = parsedAccessMode,
        boundAccountHash = boundAccountHash,
        stage = parsedStage,
        title = title,
        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
        totalBytes = totalBytes?.takeIf { it >= 0L },
        failure = enumValueOrNull<DownloadFailureCode>(failure.orEmpty()),
        rawArtifactDigestSha256 = rawArtifactDigestSha256,
        completedFileCount = completedFileCount.coerceAtLeast(0),
        pauseRequested = pauseRequested,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun DownloadTask.toEntity(): WorkshopDownloadTaskEntity = WorkshopDownloadTaskEntity(
    id = id,
    appId = appId.toLong(),
    publishedFileId = publishedFileId.toString(),
    accessMode = accessMode.name,
    boundAccountHash = boundAccountHash,
    stage = stage.name,
    title = title,
    downloadedBytes = downloadedBytes.coerceAtLeast(0L),
    totalBytes = totalBytes?.takeIf { it >= 0L },
    failure = failure?.name,
    rawArtifactDigestSha256 = rawArtifactDigestSha256,
    completedFileCount = completedFileCount.coerceAtLeast(0),
    pauseRequested = pauseRequested,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }
