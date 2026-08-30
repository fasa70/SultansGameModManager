package com.sultansgame.modmanager.platform.saveeditor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One `user_archive.json` slot as the game presents it in its own load menu.
 * Fields mirror the file's keys so a rewritten index stays readable by the game.
 */
data class SaveEditorArchiveSlot(
    val slot: Int,
    val name: String,
    val liveDays: Int,
    val leftSudan: Int,
    val executionDay: Int,
    val saveTime: String,
    val saveTimeText: String,
    val path: String,
)

/**
 * Reads and rewrites the game's `user_archive.json`, the ten-entry index behind
 * its load menu.
 *
 * Saving to a slot means two writes: the save itself to `USERARCHIVE/NNN.json`
 * and a summary row in this index. The summary numbers come from the page (it
 * owns the card catalog); this file owns the index's shape, its slot count, and
 * the display formatting, all of which are pure and therefore unit-tested.
 */
internal object SaveArchiveIndex {
    const val SLOT_COUNT = 10

    private val json = Json { ignoreUnknownKeys = true }

    /** File name the provider's path whitelist accepts for [slot]. */
    fun fileNameFor(slot: Int): String = "USERARCHIVE/%03d.json".format(slot)

    /** The slot [fileNameFor] would produce for [fileName], or null. */
    fun slotOfFileName(fileName: String): Int? {
        val leaf = fileName.removePrefix("USERARCHIVE/")
        if (leaf == fileName) return null
        return leaf.removeSuffix(".json").toIntOrNull()?.takeIf { it in 0 until SLOT_COUNT }
    }

    /**
     * Value the game stores in a slot's `path`. Upstream writes a Windows
     * separator here and the game only ever displays it, so it is reproduced
     * verbatim rather than normalised.
     */
    fun slotPathValue(slot: Int): String = "USERARCHIVE\\%03d.json".format(slot)

    /**
     * Exactly [SLOT_COUNT] raw entries. An unreadable or unexpected index reads
     * as all-empty rather than failing: the game rewrites this file itself, and
     * refusing to parse it would block slot saving entirely.
     */
    fun rawEntries(archiveJson: String?): List<JsonObject?> {
        val blank = List<JsonObject?>(SLOT_COUNT) { null }
        if (archiveJson.isNullOrBlank()) return blank
        val parsed = runCatching { json.parseToJsonElement(archiveJson) }.getOrNull() as? JsonArray
            ?: return blank
        return List(SLOT_COUNT) { index -> parsed.getOrNull(index) as? JsonObject }
    }

    fun slots(archiveJson: String?): List<SaveEditorArchiveSlot?> =
        rawEntries(archiveJson).mapIndexed { index, entry -> toSlot(index, entry) }

    /**
     * Replaces slot [slot] with a summary built from [name] and the page's
     * [pageSummaryJson], returning the index text to write plus the resulting
     * slot list. Whitespace is not preserved: the game parses this file, and a
     * compact form keeps the write furthest from the provider's size limit.
     */
    fun withSlot(
        archiveJson: String?,
        slot: Int,
        name: String,
        pageSummaryJson: String,
    ): Pair<String, List<SaveEditorArchiveSlot?>> {
        require(slot in 0 until SLOT_COUNT) { "slot out of range: $slot" }
        val summary = json.parseToJsonElement(pageSummaryJson) as? JsonObject
            ?: throw IllegalArgumentException("编辑器返回的槽位摘要不是对象")
        val entries = rawEntries(archiveJson).toMutableList()
        entries[slot] = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("live_days", JsonPrimitive(summary.intOr("live_days", -1)))
            put("left_sudan", JsonPrimitive(summary.intOr("left_sudan", 0)))
            put("execution_day", JsonPrimitive(summary.intOr("execution_day", 7)))
            put("save_time", JsonPrimitive(summary.stringOr("save_time", "")))
            put("path", JsonPrimitive(slotPathValue(slot)))
        }
        val indexJson = JsonArray(entries.map { it ?: JsonObject(emptyMap()) }).toString()
        return indexJson to entries.mapIndexed { index, entry -> toSlot(index, entry) }
    }

    /**
     * Upstream `saveArchivePage.format_iso_time`: an ISO timestamp rendered as
     * `yyyy/M/d HH:mm:ss`, with the month and day unpadded like the game shows
     * them. Anything unparsable is passed through unchanged.
     */
    fun formatIsoTime(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "保存时间未知"
        val datePart = text.substringBefore('T')
        var timePart = text.substringAfter('T', "")
        listOf(".", "+", "Z", "z").forEach { cut ->
            val index = timePart.indexOf(cut)
            if (index >= 0) timePart = timePart.substring(0, index)
        }
        if (timePart.contains('-')) timePart = timePart.substringBefore('-')
        val pieces = datePart.split("-")
        if (pieces.size != 3) return text
        val month = pieces[1].toIntOrNull() ?: return text
        val day = pieces[2].toIntOrNull() ?: return text
        return "${pieces[0]}/$month/$day ${timePart.ifEmpty { "00:00:00" }}"
    }

    private fun toSlot(slot: Int, entry: JsonObject?): SaveEditorArchiveSlot? {
        if (entry == null || entry.isEmpty()) return null
        val saveTime = entry.stringOr("save_time", "")
        return SaveEditorArchiveSlot(
            slot = slot,
            name = entry.stringOr("name", "未命名存档"),
            liveDays = entry.intOr("live_days", 0),
            leftSudan = entry.intOr("left_sudan", 0),
            executionDay = entry.intOr("execution_day", 0),
            saveTime = saveTime,
            saveTimeText = formatIsoTime(saveTime),
            path = entry.stringOr("path", ""),
        )
    }

    private fun JsonObject.intOr(key: String, fallback: Int): Int =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: fallback

    private fun JsonObject.stringOr(key: String, fallback: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fallback
}
