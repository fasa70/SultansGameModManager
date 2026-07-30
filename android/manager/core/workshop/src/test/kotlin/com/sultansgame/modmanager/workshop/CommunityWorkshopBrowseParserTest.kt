package com.sultansgame.modmanager.workshop

import com.sultansgame.modmanager.model.WorkshopBrowseQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityWorkshopBrowseParserTest {
    @Test
    fun `parses DOM cards, total count and explicit empty state`() {
        val skeleton = PublicWorkshopBrowseParser.parse(
            appId = 3117820,
            query = WorkshopBrowseQuery(page = 1),
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=3117820",
            html = """
                <div class="workshopItem">
                  <a href="/sharedfiles/filedetails/?id=12345">
                    <img src="https://steamusercontent-a.akamaihd.net/preview.jpg" alt="Fallback title" />
                    <div class="workshopItemTitle">Better Sultan UI</div>
                  </a>
                  <div class="workshopItemAuthorName">Mod Author</div>
                </div>
                <div>Showing 1 - 1 of 42 results</div>
            """.trimIndent(),
        )

        assertEquals(42, skeleton.totalCount)
        assertEquals(1, skeleton.items.size)
        assertEquals(12345L, skeleton.items.single().publishedFileId)
        assertEquals("Better Sultan UI", skeleton.items.single().title)
        assertEquals("Mod Author", skeleton.items.single().authorName)
        assertEquals("https://steamusercontent-a.akamaihd.net/preview.jpg", skeleton.items.single().previewUrl)
        assertFalse(skeleton.isExplicitlyEmpty)
    }

    @Test
    fun `uses hover order when HTML cards duplicate item links`() {
        val skeleton = PublicWorkshopBrowseParser.parse(
            appId = 3117820,
            query = WorkshopBrowseQuery(page = 2),
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=3117820&p=2",
            html = """
                <a href="/sharedfiles/filedetails/?id=2">Second</a>
                <a href="/sharedfiles/filedetails/?id=1">First</a>
                <script>
                  SharedFileBindMouseHover("sharedfile_1", false, {"title":"First"});
                  SharedFileBindMouseHover("sharedfile_2", false, {"title":"Second"});
                </script>
            """.trimIndent(),
        )

        assertEquals(listOf(1L, 2L), skeleton.items.map(PublicWorkshopBrowseItemSkeleton::publishedFileId))
        assertEquals(2, skeleton.maxPage)
    }

    @Test
    fun `recognizes localized empty browse response`() {
        val skeleton = PublicWorkshopBrowseParser.parse(
            appId = 3117820,
            query = WorkshopBrowseQuery(searchText = "不存在的条目"),
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=3117820",
            html = "<p>没有可显示的创意工坊条目</p>",
        )

        assertTrue(skeleton.items.isEmpty())
        assertTrue(skeleton.isExplicitlyEmpty)
    }
}
