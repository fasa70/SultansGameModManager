package com.sultansgame.modmanager.platform.workshop

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.model.SULTANS_GAME_APP_ID
import com.sultansgame.modmanager.model.WorkshopAccessMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `active progress never overwrites paused task`() = runBlocking {
        dao.upsert(task(stage = DownloadStage.Downloading).toEntity())
        dao.forceState(
            id = TASK_ID,
            stage = DownloadStage.Paused.name,
            failure = null,
            pauseRequested = true,
            updatedAtEpochMillis = 20L,
        )

        val updatedRows = dao.updateActiveProgress(
            id = TASK_ID,
            stage = DownloadStage.Downloading.name,
            downloadedBytes = 1024L,
            totalBytes = 2048L,
            updatedAtEpochMillis = 30L,
        )
        val current = requireNotNull(dao.get(TASK_ID)?.toModel())

        assertEquals(0, updatedRows)
        assertEquals(DownloadStage.Paused, current.stage)
        assertEquals(0L, current.downloadedBytes)
        assertEquals(true, current.pauseRequested)
    }

    @Test
    fun `completion never overwrites a cancelled task`() = runBlocking {
        dao.upsert(task(stage = DownloadStage.Downloading).toEntity())
        dao.forceState(
            id = TASK_ID,
            stage = DownloadStage.Cancelled.name,
            failure = "Cancelled",
            pauseRequested = false,
            updatedAtEpochMillis = 20L,
        )

        val updatedRows = dao.completeDownload(
            id = TASK_ID,
            rawArtifactDigestSha256 = "a".repeat(64),
            completedFileCount = 1,
            updatedAtEpochMillis = 30L,
        )
        val current = requireNotNull(dao.get(TASK_ID)?.toModel())

        assertEquals(0, updatedRows)
        assertEquals(DownloadStage.Cancelled, current.stage)
        assertNull(current.rawArtifactDigestSha256)
        assertEquals(0, current.completedFileCount)
    }

    @Test
    fun `valid download completion stores import summary`() = runBlocking {
        dao.upsert(task(stage = DownloadStage.Verifying).toEntity())

        val updatedRows = dao.completeDownload(
            id = TASK_ID,
            rawArtifactDigestSha256 = "b".repeat(64),
            completedFileCount = 3,
            updatedAtEpochMillis = 30L,
        )
        val current = requireNotNull(dao.get(TASK_ID)?.toModel())

        assertEquals(1, updatedRows)
        assertEquals(DownloadStage.AwaitingImportConfirmation, current.stage)
        assertEquals("b".repeat(64), current.rawArtifactDigestSha256)
        assertEquals(3, current.completedFileCount)
    }

    private fun task(stage: DownloadStage): DownloadTask = DownloadTask(
        id = TASK_ID,
        appId = SULTANS_GAME_APP_ID,
        publishedFileId = PublishedFileId.parse("123")!!,
        accessMode = WorkshopAccessMode.Anonymous,
        stage = stage,
        createdAtEpochMillis = 10L,
        updatedAtEpochMillis = 10L,
    )

    private companion object {
        const val TASK_ID = "421b2430-5c96-4c45-9b10-46a177bdde0c"
    }
}
