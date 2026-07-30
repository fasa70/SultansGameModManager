package com.sultansgame.modmanager.workshop

import com.sultansgame.modmanager.model.PublishedFileId
import org.jsoup.Jsoup

data class PublicWorkshopDetail(
    val authorName: String? = null,
    val authorProfileUrl: String? = null,
    val previewUrl: String? = null,
)

interface PublicWorkshopDetailTransport {
    fun getPublishedFileDetail(publishedFileId: PublishedFileId): PublicWorkshopDetail?
}

/** Parses only presentation metadata from a Steam Community detail page. */
object PublicWorkshopDetailParser {
    fun parse(html: String): PublicWorkshopDetail? {
        val document = Jsoup.parse(html)
        val authorLink = document.selectFirst(
            ".creatorsBlock a.friendBlockLinkOverlay[href], .creatorsBlock .friendBlockContent a[href], .friendBlockContent a[href]",
        )
        val authorProfileUrl = authorLink?.absUrl("href")?.takeIf(WorkshopHttpPolicy::isAllowedAuthorProfileUrl)
        val authorName = sequenceOf(
            document.selectFirst(".creatorsBlock .friendBlockContent"),
            authorLink,
        ).mapNotNull { element -> element?.text()?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
        val previewUrl = document.selectFirst("#previewImageMain[src], .workshopItemPreviewImageMain[src], .workshopItemPreviewImage[src]")
            ?.absUrl("src")
            ?.takeIf(WorkshopHttpPolicy::isAllowedPreviewImageUrl)
        return PublicWorkshopDetail(authorName, authorProfileUrl, previewUrl)
            .takeIf { it.authorName != null || it.authorProfileUrl != null || it.previewUrl != null }
    }
}
