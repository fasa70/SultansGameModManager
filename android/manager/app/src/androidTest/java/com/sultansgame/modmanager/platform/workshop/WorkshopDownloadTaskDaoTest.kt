package com.sultansgame.modmanager.platform.workshop

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.model.SULTANS_GAME_APP_ID
import com.sultansgame.modmanager.model.WorkshopAccessMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkshopDownloadTaskDaoTest {
    private lateinit var database: WorkshopDownloadDatabase
    private lateinit var dao: WorkshopDownloadTaskDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WorkshopDownloadDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.taskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `old generation cannot overwrite retried task`() = runBlocking {
        dao.insert(task(stage = DownloadStage.Downloading, generation = 1L).toEntity())

        assertEquals(1, dao.requestPause(TASK_ID, 20L))
        assertEquals(1, dao.requestRetry(TASK_ID, 30L))
        val current = requireNotNull(dao.get(TASK_ID)?.toModel())

        assertEquals(2L, current.attemptGeneration)
        assertEquals(0, dao.updateActiveProgress(
            id = TASK_ID,
            attemptGeneration = 1L,
            stage = DownloadStage.Downloading.name,
            downloadedBytes = 1024L,
            totalBytes = 2048L,
            updatedAtEpochMillis = 40L,
        ))
        assertEquals(0, dao.completeDownload(
            id = TASK_ID,
            attemptGeneration = 1L,
            rawArtifactDigestSha256 = "a".repeat(64),
            completedFileCount = 1,
            updatedAtEpochMillis = 40L,
        ))

        val unchanged = requireNotNull(dao.get(TASK_ID)?.toModel())
        assertEquals(DownloadStage.Queued, unchanged.stage)
        assertEquals(2L, unchanged.attemptGeneration)
        assertEquals(0L, unchanged.downloadedBytes)
    }

    @Test
    fun `completion cannot overwrite paused or cancelled task`() = runBlocking {
        dao.insert(task(stage = DownloadStage.Downloading).toEntity())
        assertEquals(1, dao.requestPause(TASK_ID, 20L))

        assertEquals(0, dao.completeDownload(
            id = TASK_ID,
            attemptGeneration = 1L,
            rawArtifactDigestSha256 = "a".repeat(64),
            completedFileCount = 1,
            updatedAtEpochMillis = 30L,
        ))
        assertEquals(DownloadStage.Paused, requireNotNull(dao.get(TASK_ID)?.toModel()).stage)

        assertEquals(1, dao.requestCancel(TASK_ID, 40L))
        assertEquals(0, dao.completeDownload(
            id = TASK_ID,
            attemptGeneration = 1L,
            rawArtifactDigestSha256 = "b".repeat(64),
            completedFileCount = 1,
            updatedAtEpochMillis = 50L,
        ))
        val cancelled = requireNotNull(dao.get(TASK_ID)?.toModel())
        assertEquals(DownloadStage.Cancelled, cancelled.stage)
        assertNull(cancelled.rawArtifactDigestSha256)
    }

    @Test
    fun `only one caller claims import`() = runBlocking {
        dao.insert(task(stage = DownloadStage.AwaitingImportConfirmation).copy(
            rawArtifactDigestSha256 = "b".repeat(64),
            completedFileCount = 1,
        ).toEntity())

        val claims = listOf(
            async { dao.beginImport(TASK_ID, 20L) },
            async { dao.beginImport(TASK_ID, 20L) },
        ).awaitAll()

        assertEquals(1, claims.count { it == 1 })
        assertEquals(DownloadStage.Importing, requireNotNull(dao.get(TASK_ID)?.toModel()).stage)
    }

    @Test
    fun `interrupted active task is requeued with new generation`() = runBlocking {
        dao.insert(task(stage = DownloadStage.Downloading, generation = 4L).toEntity())

        assertEquals(1, dao.requeueInterruptedDownloads(20L))
        val current = requireNotNull(dao.get(TASK_ID)?.toModel())

        assertEquals(DownloadStage.Queued, current.stage)
        assertEquals(5L, current.attemptGeneration)
        assertEquals(0L, current.downloadedBytes)
        assertFalse(current.pauseRequested)
    }

    @Test
    fun `interrupted import returns to explicit user confirmation`() = runBlocking {
        dao.insert(task(stage = DownloadStage.Importing).copy(
            rawArtifactDigestSha256 = "b".repeat(64),
            completedFileCount = 3,
        ).toEntity())

        assertEquals(1, dao.restoreInterruptedImports(20L))
        val current = requireNotNull(dao.get(TASK_ID)?.toModel())

        assertEquals(DownloadStage.AwaitingImportConfirmation, current.stage)
        assertTrue(current.rawArtifactDigestSha256?.matches(Regex("[0-9a-f]{64}")) == true)
    }

    private fun task(stage: DownloadStage, generation: Long = 1L): DownloadTask = DownloadTask(
        id = TASK_ID,
        appId = SULTANS_GAME_APP_ID,
        publishedFileId = PublishedFileId.parse("123")!!,
        accessMode = WorkshopAccessMode.Anonymous,
        stage = stage,
        attemptGeneration = generation,
        createdAtEpochMillis = 10L,
        updatedAtEpochMillis = 10L,
    )

    private companion object {
        const val TASK_ID = "421b2430-5c96-4c45-9b10-46a177bdde0c"
    }
}
