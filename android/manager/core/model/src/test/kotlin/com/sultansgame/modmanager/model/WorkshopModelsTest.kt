package com.sultansgame.modmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `browse query normalizes filters dates and paging`() {
        val normalized = WorkshopBrowseQuery(
            sectionKey = "  ",
            sortKey = " ",
            searchText = "  Sultan  ",
            requiredTags = setOf("  UI ", "", "UI"),
            excludedTags = setOf("  NSFW ", "UI"),
            createdDateRange = WorkshopDateRangeFilter(20L, 10L),
            page = 0,
            pageSize = 10,
        ).normalized()

        assertEquals(WorkshopBrowseQuery.SECTION_ITEMS, normalized.sectionKey)
        assertEquals(WorkshopBrowseQuery.SORT_TREND, normalized.sortKey)
        assertEquals("Sultan", normalized.searchText)
        assertEquals(setOf("UI"), normalized.requiredTags)
        assertEquals(setOf("NSFW"), normalized.excludedTags)
        assertEquals(10L, normalized.createdDateRange.startEpochSeconds)
        assertEquals(20L, normalized.createdDateRange.endEpochSeconds)
        assertEquals(1, normalized.page)
        assertEquals(WorkshopBrowseQuery.DEFAULT_PAGE_SIZE, normalized.pageSize)
    }

    @Test
    fun `workshop item accepts either a direct url or a Steam content manifest`() {
        val id = PublishedFileId.parse("123")!!
        val noDownload = WorkshopItem(
            appId = SULTANS_GAME_APP_ID,
            publishedFileId = id,
            title = "Test",
            updatedAtEpochSeconds = null,
            fileUrl = null,
            previewUrl = null,
            declaredSizeBytes = null,
            availability = WorkshopAvailability.Unavailable,
        )
        val directDownload = noDownload.copy(fileUrl = "https://steamusercontent-a.akamaihd.net/mod.zip")
        val manifestDownload = noDownload.copy(contentManifestId = 123456789uL)

        assertFalse(noDownload.canDownload)
        assertTrue(directDownload.canDirectDownload)
        assertTrue(directDownload.canDownload)
        assertTrue(manifestDownload.canSteamContentDownload)
        assertTrue(manifestDownload.canDownload)
        assertFalse(noDownload.copy(contentManifestId = 0u).canSteamContentDownload)
    }
}
