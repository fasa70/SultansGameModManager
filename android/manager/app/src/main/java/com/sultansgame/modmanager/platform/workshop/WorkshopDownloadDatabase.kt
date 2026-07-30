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
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.PublishedFileId
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
    val attemptGeneration: Long,
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: WorkshopDownloadTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForMigration(task: WorkshopDownloadTaskEntity)

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = 'Paused',
            failure = NULL,
            pauseRequested = 1,
            attemptGeneration = attemptGeneration + 1,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
          AND stage IN ('Queued', 'ResolvingMetadata', 'AwaitingPublicUrl', 'Downloading', 'Verifying')
    """)
    suspend fun requestPause(id: String, updatedAtEpochMillis: Long): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = 'Cancelled',
            failure = 'Cancelled',
            pauseRequested = 0,
            attemptGeneration = attemptGeneration + 1,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
          AND stage NOT IN ('Cancelled', 'Imported', 'Importing')
    """)
    suspend fun requestCancel(id: String, updatedAtEpochMillis: Long): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = 'Queued',
            failure = NULL,
            pauseRequested = 0,
            downloadedBytes = 0,
            totalBytes = NULL,
            rawArtifactDigestSha256 = NULL,
            completedFileCount = 0,
            attemptGeneration = attemptGeneration + 1,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
          AND stage IN ('Paused', 'Failed', 'NeedsLogin')
    """)
    suspend fun requestRetry(id: String, updatedAtEpochMillis: Long): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = :stage,
            failure = :failure,
            pauseRequested = 0,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
          AND attemptGeneration = :attemptGeneration
          AND stage IN ('Queued', 'ResolvingMetadata', 'AwaitingPublicUrl', 'Downloading', 'Verifying')
    """)
    suspend fun updateActiveState(
        id: String,
        attemptGeneration: Long,
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
          AND attemptGeneration = :attemptGeneration
          AND pauseRequested = 0
          AND stage IN ('Queued', 'ResolvingMetadata', 'AwaitingPublicUrl', 'Downloading', 'Verifying')
    """)
    suspend fun updateActiveProgress(
        id: String,
        attemptGeneration: Long,
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
          AND attemptGeneration = :attemptGeneration
          AND pauseRequested = 0
          AND stage IN ('ResolvingMetadata', 'AwaitingPublicUrl', 'Downloading', 'Verifying')
    """)
    suspend fun completeDownload(
        id: String,
        attemptGeneration: Long,
        rawArtifactDigestSha256: String,
        completedFileCount: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = 'Importing',
            failure = NULL,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
          AND stage = 'AwaitingImportConfirmation'
          AND rawArtifactDigestSha256 IS NOT NULL
          AND completedFileCount > 0
    """)
    suspend fun beginImport(id: String, updatedAtEpochMillis: Long): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = 'Imported',
            failure = NULL,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
          AND stage = 'Importing'
    """)
    suspend fun finishImport(id: String, updatedAtEpochMillis: Long): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = 'AwaitingImportConfirmation',
            failure = :failure,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
          AND stage = 'Importing'
    """)
    suspend fun failImport(id: String, failure: String, updatedAtEpochMillis: Long): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = 'Queued',
            failure = NULL,
            pauseRequested = 0,
            downloadedBytes = 0,
            totalBytes = NULL,
            rawArtifactDigestSha256 = NULL,
            completedFileCount = 0,
            attemptGeneration = attemptGeneration + 1,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE stage IN ('ResolvingMetadata', 'AwaitingPublicUrl', 'Downloading', 'Verifying')
    """)
    suspend fun requeueInterruptedDownloads(updatedAtEpochMillis: Long): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = 'AwaitingImportConfirmation',
            failure = NULL,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE stage = 'Importing'
          AND rawArtifactDigestSha256 IS NOT NULL
          AND completedFileCount > 0
    """)
    suspend fun restoreInterruptedImports(updatedAtEpochMillis: Long): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = 'Failed',
            failure = 'InvalidArtifact',
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE stage IN ('AwaitingImportConfirmation', 'Importing')
          AND (rawArtifactDigestSha256 IS NULL OR completedFileCount <= 0)
    """)
    suspend fun invalidateUnverifiableImports(updatedAtEpochMillis: Long): Int

    @Query("""
        UPDATE workshop_download_tasks
        SET stage = 'Failed',
            failure = 'InvalidArtifact',
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
          AND stage = 'AwaitingImportConfirmation'
    """)
    suspend fun invalidateArtifact(id: String, updatedAtEpochMillis: Long): Int

    @Query("DELETE FROM workshop_download_tasks WHERE id = :id AND stage != 'Importing'")
    suspend fun removeUnlessImporting(id: String): Int

    @Transaction
    suspend fun takeAndRemoveUnlessImporting(id: String): WorkshopDownloadTaskEntity? {
        val task = get(id) ?: return null
        return task.takeIf { removeUnlessImporting(id) == 1 }
    }
}

@Database(
    entities = [WorkshopDownloadTaskEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class WorkshopDownloadDatabase : RoomDatabase() {
    abstract fun taskDao(): WorkshopDownloadTaskDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE workshop_download_tasks ADD COLUMN attemptGeneration INTEGER NOT NULL DEFAULT 1",
                )
            }
        }
    }
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
        attemptGeneration = attemptGeneration.coerceAtLeast(1L),
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
    attemptGeneration = attemptGeneration.coerceAtLeast(1L),
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }
