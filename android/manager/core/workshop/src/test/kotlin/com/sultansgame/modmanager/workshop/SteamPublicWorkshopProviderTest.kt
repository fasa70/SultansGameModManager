package com.sultansgame.modmanager.workshop

import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.model.WorkshopAccessMode
import com.sultansgame.modmanager.model.WorkshopAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `merges author detail and keeps preview policy separate from artifacts`() {
        val provider = SteamPublicWorkshopProvider(
            transport = FakeTransport(
                PublicWorkshopMetadata(
                    consumerAppId = 3117820u,
                    publishedFileId = itemId,
                    resultCode = 1,
                    title = "公开 Mod",
                    fileUrl = "https://steamusercontent-a.akamaihd.net/workshop.zip",
                    previewUrl = "https://images.akamai.steamusercontent.com/preview.jpg",
                    updatedAtEpochSeconds = null,
                    declaredSizeBytes = 10,
                ),
            ),
            detailTransport = object : PublicWorkshopDetailTransport {
                override fun getPublishedFileDetail(publishedFileId: PublishedFileId) = PublicWorkshopDetail(
                    authorName = "Steam 作者",
                    authorProfileUrl = "https://steamcommunity.com/profiles/76561198000000000/",
                )
            },
        )

        val item = (provider.getItemWithCommunityDetail(3117820u, itemId) as WorkshopLookupResult.Available).item

        assertEquals("Steam 作者", item.authorName)
        assertEquals("https://steamcommunity.com/profiles/76561198000000000/", item.authorProfileUrl)
        assertEquals("https://images.akamai.steamusercontent.com/preview.jpg", item.previewUrl)
        assertFalse(WorkshopHttpPolicy.isAllowedArtifactUrl("https://images.akamai.steamusercontent.com/preview.jpg"))
        assertTrue(WorkshopHttpPolicy.isAllowedPreviewImageUrl("https://images.akamai.steamusercontent.com/preview.jpg"))
    }

    @Test
    fun `rejects unsafe author profile and preview urls`() {
        assertFalse(WorkshopHttpPolicy.isAllowedAuthorProfileUrl("https://steamcommunity.com.evil.test/profiles/76561198000000000/"))
        assertFalse(WorkshopHttpPolicy.isAllowedAuthorProfileUrl("https://steamcommunity.com/profiles/not-a-number/"))
        assertFalse(WorkshopHttpPolicy.isAllowedPreviewImageUrl("http://steamusercontent-a.akamaihd.net/preview.jpg"))
        assertFalse(WorkshopHttpPolicy.isAllowedPreviewImageUrl("https://127.0.0.1/preview.jpg"))
        assertNull(PublicWorkshopDetailParser.parse("<div>no usable detail</div>"))
    }

    @Test
    fun `normalizes only trusted default-port http preview urls`() {
        assertEquals(
            "https://images.akamai.steamusercontent.com/preview.jpg?size=large",
            WorkshopHttpPolicy.normalizePreviewImageUrl(
                "http://images.akamai.steamusercontent.com/preview.jpg?size=large#ignored",
            ),
        )
        assertEquals(
            "https://images.akamai.steamusercontent.com/preview.jpg",
            WorkshopHttpPolicy.normalizePreviewImageUrl(
                "https://images.akamai.steamusercontent.com/preview.jpg#ignored",
            ),
        )
        assertNull(WorkshopHttpPolicy.normalizePreviewImageUrl("http://images.akamai.steamusercontent.com:8080/preview.jpg"))
        assertNull(WorkshopHttpPolicy.normalizePreviewImageUrl("http://steamusercontent-a.akamaihd.net.evil.test/preview.jpg"))
        assertNull(WorkshopHttpPolicy.normalizePreviewImageUrl("http://127.0.0.1/preview.jpg"))
        assertNull(WorkshopHttpPolicy.normalizePreviewImageUrl("http://user@steamusercontent-a.akamaihd.net/preview.jpg"))
    }

    @Test
    fun `uses normalized metadata preview before community detail preview`() {
        val provider = SteamPublicWorkshopProvider(
            transport = FakeTransport(
                PublicWorkshopMetadata(
                    consumerAppId = 3117820u,
                    publishedFileId = itemId,
                    resultCode = 1,
                    title = "公开 Mod",
                    fileUrl = "https://steamusercontent-a.akamaihd.net/workshop.zip",
                    previewUrl = "http://steamusercontent-a.akamaihd.net/preview.jpg",
                    updatedAtEpochSeconds = null,
                    declaredSizeBytes = 10,
                ),
            ),
            detailTransport = object : PublicWorkshopDetailTransport {
                override fun getPublishedFileDetail(publishedFileId: PublishedFileId) = PublicWorkshopDetail(
                    previewUrl = "https://images.akamai.steamusercontent.com/detail.jpg",
                )
            },
        )

        val item = (provider.getItemWithCommunityDetail(3117820u, itemId) as WorkshopLookupResult.Available).item

        assertEquals("https://steamusercontent-a.akamaihd.net/preview.jpg", item.previewUrl)
    }

    private class FakeTransport(private val result: PublicWorkshopMetadata) : PublicWorkshopMetadataTransport {
        override fun getPublishedFileDetails(appId: UInt, publishedFileId: PublishedFileId): PublicWorkshopMetadata = result
    }
}
