package com.sultansgame.modmanager.platform.saveeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `user_archive.json` contract: the game reads this file back, so slot
 * count, field names, and the display formatting all have to stay put.
 */
class SaveArchiveIndexTest {
    private val summary =
        """{"live_days":12,"left_sudan":3,"execution_day":5,"save_time":"2026-08-30T21:04:07.123Z"}"""

    @Test
    fun fileNameMatchesTheProviderWhitelist() {
        assertEquals("USERARCHIVE/000.json", SaveArchiveIndex.fileNameFor(0))
        assertEquals("USERARCHIVE/009.json", SaveArchiveIndex.fileNameFor(9))
    }

    @Test
    fun slotPathKeepsUpstreamBackslash() {
        assertEquals("USERARCHIVE\\003.json", SaveArchiveIndex.slotPathValue(3))
    }

    @Test
    fun recognisesItsOwnSlotFileNames() {
        assertEquals(0, SaveArchiveIndex.slotOfFileName("USERARCHIVE/000.json"))
        assertEquals(9, SaveArchiveIndex.slotOfFileName("USERARCHIVE/009.json"))
        assertNull(SaveArchiveIndex.slotOfFileName("USERARCHIVE/010.json"))
        assertNull(SaveArchiveIndex.slotOfFileName("auto_save.json"))
        assertNull(SaveArchiveIndex.slotOfFileName("USERARCHIVE/abc.json"))
    }

    @Test
    fun missingIndexReadsAsTenEmptySlots() {
        val slots = SaveArchiveIndex.slots(null)
        assertEquals(10, slots.size)
        assertTrue(slots.all { it == null })
    }

    @Test
    fun unparsableIndexReadsAsEmptyRatherThanFailing() {
        assertTrue(SaveArchiveIndex.slots("not json at all").all { it == null })
        assertTrue(SaveArchiveIndex.slots("""{"slots":[]}""").all { it == null })
    }

    @Test
    fun readsExistingSlotsAndFormatsTime() {
        val index = """
            [{"name":"第一天","live_days":1,"left_sudan":4,"execution_day":6,
              "save_time":"2026-03-06T16:03:33","path":"USERARCHIVE\\000.json"},{}]
        """.trimIndent()
        val slots = SaveArchiveIndex.slots(index)
        val first = requireNotNull(slots[0])
        assertEquals("第一天", first.name)
        assertEquals(1, first.liveDays)
        assertEquals(4, first.leftSudan)
        assertEquals(6, first.executionDay)
        assertEquals("2026/3/6 16:03:33", first.saveTimeText)
        assertNull(slots[1])
        assertEquals(10, slots.size)
    }

    @Test
    fun writesSummaryIntoTheRequestedSlotOnly() {
        val existing = """[{"name":"旧存档","live_days":9,"left_sudan":1,"execution_day":2,
            "save_time":"2026-01-02T03:04:05","path":"USERARCHIVE\\000.json"}]"""
        val (indexJson, slots) = SaveArchiveIndex.withSlot(existing, 2, "新存档", summary)
        assertEquals("旧存档", requireNotNull(slots[0]).name)
        assertNull(slots[1])
        val written = requireNotNull(slots[2])
        assertEquals("新存档", written.name)
        assertEquals(12, written.liveDays)
        assertEquals(3, written.leftSudan)
        assertEquals(5, written.executionDay)
        assertEquals("USERARCHIVE\\002.json", written.path)
        // The written text must round-trip through the reader unchanged.
        assertEquals(slots, SaveArchiveIndex.slots(indexJson))
    }

    @Test
    fun writtenIndexAlwaysHasTenEntries() {
        val (indexJson, _) = SaveArchiveIndex.withSlot(null, 9, "尾槽", summary)
        assertEquals(10, SaveArchiveIndex.slots(indexJson).size)
        // Empty slots stay as objects so the game sees a fixed-length array.
        assertEquals(9, Regex("\\{}").findAll(indexJson).count())
    }

    @Test
    fun overwritingASlotReplacesItRatherThanAppending() {
        val (first, _) = SaveArchiveIndex.withSlot(null, 4, "第一次", summary)
        val (second, slots) = SaveArchiveIndex.withSlot(first, 4, "第二次", summary)
        assertEquals("第二次", requireNotNull(slots[4]).name)
        assertEquals(10, SaveArchiveIndex.slots(second).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeSlot() {
        SaveArchiveIndex.withSlot(null, 10, "越界", summary)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonObjectPageSummary() {
        SaveArchiveIndex.withSlot(null, 0, "坏摘要", "[1,2,3]")
    }

    @Test
    fun timeFormattingHandlesOffsetsFractionsAndJunk() {
        assertEquals("2026/8/30 21:04:07", SaveArchiveIndex.formatIsoTime("2026-08-30T21:04:07.123Z"))
        assertEquals("2026/8/30 21:04:07", SaveArchiveIndex.formatIsoTime("2026-08-30T21:04:07+08:00"))
        assertEquals("2026/8/30 21:04:07", SaveArchiveIndex.formatIsoTime("2026-08-30T21:04:07-05:00"))
        assertEquals("2026/8/30 00:00:00", SaveArchiveIndex.formatIsoTime("2026-08-30"))
        assertEquals("保存时间未知", SaveArchiveIndex.formatIsoTime(null))
        assertEquals("保存时间未知", SaveArchiveIndex.formatIsoTime("   "))
        assertEquals("昨天", SaveArchiveIndex.formatIsoTime("昨天"))
    }
}
