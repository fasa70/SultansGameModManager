package com.sultansgame.modmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkshopModelsTest {
    @Test
    fun `published file id accepts only positive decimal values`() {
        assertNotNull(PublishedFileId.parse("123456"))
        assertNull(PublishedFileId.parse("0"))
        assertNull(PublishedFileId.parse("-1"))
        assertNull(PublishedFileId.parse("12a"))
    }

    @Test
    fun `download task defaults to the Sultan app id`() {
        val id = PublishedFileId.parse("123")!!
        val task = DownloadTask(
            id = "task",
            appId = SULTANS_GAME_APP_ID,
            publishedFileId = id,
            accessMode = WorkshopAccessMode.Account,
            stage = DownloadStage.AwaitingImportConfirmation,
        )

        assertEquals(3117820u, task.appId)
        assertEquals(DownloadStage.AwaitingImportConfirmation, task.stage)
        assertEquals(0, task.completedFileCount)
    }
}
