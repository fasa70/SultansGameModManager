package com.sultansgame.modmanager.workshop

import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.model.WorkshopAccessMode
import com.sultansgame.modmanager.model.WorkshopAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamPublicWorkshopProviderTest {
    private val itemId = PublishedFileId.parse("3489067376")!!

    @Test
    fun `accepts only an allowed public artifact url for the requested game`() {
        val provider = SteamPublicWorkshopProvider(FakeTransport(
            PublicWorkshopMetadata(
                consumerAppId = 3117820u,
                publishedFileId = itemId,
                resultCode = 1,
                title = "公开 Mod",
                fileUrl = "https://steamusercontent-a.akamaihd.net/workshop.zip",
                previewUrl = null,
                updatedAtEpochSeconds = null,
                declaredSizeBytes = 10,
            ),
        ))

        val result = provider.getItem(3117820u, itemId, WorkshopAccessMode.Anonymous)

        assertEquals(WorkshopAvailability.PublicDownloadAvailable, (result as WorkshopLookupResult.Available).item.availability)
    }

    @Test
    fun `rejects metadata for a different game`() {
        val provider = SteamPublicWorkshopProvider(FakeTransport(
            PublicWorkshopMetadata(
                consumerAppId = 1u,
                publishedFileId = itemId,
                resultCode = 1,
                title = "其他游戏",
                fileUrl = "https://steamusercontent-a.akamaihd.net/workshop.zip",
                previewUrl = null,
                updatedAtEpochSeconds = null,
                declaredSizeBytes = null,
            ),
        ))

        assertTrue(provider.getItem(3117820u, itemId, WorkshopAccessMode.Anonymous) is WorkshopLookupResult.Unavailable)
    }

    @Test
    fun `does not trust arbitrary metadata urls`() {
        val provider = SteamPublicWorkshopProvider(FakeTransport(
            PublicWorkshopMetadata(
                consumerAppId = 3117820u,
                publishedFileId = itemId,
                resultCode = 1,
                title = "不安全",
                fileUrl = "http://127.0.0.1/archive.zip",
                previewUrl = null,
                updatedAtEpochSeconds = null,
                declaredSizeBytes = null,
            ),
        ))

        val result = provider.getItem(3117820u, itemId, WorkshopAccessMode.Anonymous)

        assertEquals(WorkshopAvailability.Unavailable, (result as WorkshopLookupResult.Available).item.availability)
        assertFalse(WorkshopHttpPolicy.isAllowedArtifactUrl("http://127.0.0.1/archive.zip"))
    }

    @Test
    fun `rejects nonpositive or nonnumeric published ids`() {
        assertEquals(null, PublishedFileId.parse("0"))
        assertEquals(null, PublishedFileId.parse("abc"))
        assertTrue(PublishedFileId.parse("1") != null)
    }

    private class FakeTransport(private val result: PublicWorkshopMetadata) : PublicWorkshopMetadataTransport {
        override fun getPublishedFileDetails(appId: UInt, publishedFileId: PublishedFileId): PublicWorkshopMetadata = result
    }
}
