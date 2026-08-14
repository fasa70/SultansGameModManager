package com.sultansgame.modmanager.merge

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModMergeEngineTest {
    private val json = Json
    private val catalog = BaseIdCatalog(
        profileId = "test",
        versionCode = 1,
        catalogVersion = "test",
        cards = setOf("base-card"),
    )
    private val selection = CatalogSelection(catalog, exactVersion = true)

    @Test
    fun catalogCodecAcceptsNumericArrays() {
        val decoded = BaseIdCatalogJsonCodec().decode(
            """
            {
              "profileId": "test",
              "versionCode": 1,
              "catalogVersion": "test",
              "cards": ["2000001"],
              "tagCodes": ["physique"],
              "tagIds": [3000001],
              "tagNames": ["体魄"],
              "over": ["0"],
              "rite": ["5000001"],
              "event": ["5300000"],
              "loot": ["6000004"],
              "riteTemplate": ["8000001"],
              "riteTemplateMappings": ["0"]
            }
            """.trimIndent(),
        )

        assertEquals(setOf(3000001), decoded.tagIds)
        assertEquals(setOf("体魄"), decoded.tagNames)
    }

    @Test
    fun catalogCodecDecodesCheckedInCatalog() {
        val catalogFile = Path.of("../../app/src/main/assets/merge/base-id-catalog-10005.json")
        val decoded = BaseIdCatalogJsonCodec().decode(catalogFile.readText())

        assertEquals("official-android-2026-07-27", decoded.profileId)
        assertEquals(10005L, decoded.versionCode)
        assertEquals(1292, decoded.cards.size)
        assertEquals(442, decoded.tagCodes.size)
        assertEquals(427, decoded.tagIds.size)
        assertEquals(442, decoded.tagNames.size)
    }
    @Test
    fun catalogSelectorFallsBackWithWarningOnVersionMismatch() {
        val selected = BaseIdCatalogSelector(listOf(catalog)).select("test", 2)
        assertEquals(catalog, selected?.catalog)
        assertFalse(selected?.exactVersion ?: true)
        assertTrue(selected?.warning?.contains("不匹配") == true)
    }

    @Test
    fun catalogSelectorUsesSoleCatalogWhenProfileIsUnknown() {
        val selected = BaseIdCatalogSelector(listOf(catalog)).select("other", 2)
        assertEquals(catalog, selected?.catalog)
        assertFalse(selected?.exactVersion ?: true)
    }

    @Test
    fun overlayKeepsMissingFieldsAndReplacesNormalArrays() = withTempDirectory { root ->
        val low = root.resolve("low").createDirectories()
        val high = root.resolve("high").createDirectories()
        writeJson(low, "config/test.json", """
            { "settings": { "keep": 1, "change": 1 }, "values": [1, 2] }
        """.trimIndent())
        writeJson(high, "config/test.json", """
            { "settings": { "change": 2 }, "values": [3] }
        """.trimIndent())

        val output = root.resolve("output")
        ModMergeEngine().merge(
            listOf(low.toFile(), high.toFile()), selection, output.toFile(), listOf("low", "high"),
        )

        val document = json.parseToJsonElement(output.resolve("config/test.json").readText()).jsonObject
        assertEquals("1", document.getValue("settings").jsonObject.getValue("keep").jsonPrimitive.content)
        assertEquals("2", document.getValue("settings").jsonObject.getValue("change").jsonPrimitive.content)
        assertEquals("3", document.getValue("values").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun specialArraysMergeByIdentityAndSfxUsesLastFile() = withTempDirectory { root ->
        val low = root.resolve("low").createDirectories()
        val high = root.resolve("high").createDirectories()
        writeJson(low, "config/events.json", """
            { "settlement": [{ "guid": "a", "x": 1 }] }
        """.trimIndent())
        writeJson(high, "config/events.json", """
            { "settlement": [{ "guid": "a", "y": 2 }] }
        """.trimIndent())
        writeJson(low, "config/sfx_config.json", """{ "value": "low" }""")
        writeJson(high, "config/sfx_config.json", """{ "value": "high" }""")

        val output = root.resolve("output")
        ModMergeEngine().merge(listOf(low.toFile(), high.toFile()), selection, output.toFile())

        val event = json.parseToJsonElement(output.resolve("config/events.json").readText())
            .jsonObject.getValue("settlement").jsonArray.single().jsonObject
        assertEquals("1", event.getValue("x").jsonPrimitive.content)
        assertEquals("2", event.getValue("y").jsonPrimitive.content)
        val sfx = json.parseToJsonElement(output.resolve("config/sfx_config.json").readText()).jsonObject
        assertEquals("high", sfx.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun commentsTrailingCommaAndControlCharactersAreHandled() = withTempDirectory { root ->
        val low = root.resolve("low").createDirectories()
        val high = root.resolve("high").createDirectories()
        writeJson(low, "config/a.json", """
            {
              // comment
              "text": "line\nvalue",
            }
        """.trimIndent())
        writeJson(high, "config/b.json", """{ "ok": true }""")

        val output = root.resolve("output")
        ModMergeEngine().merge(listOf(low.toFile(), high.toFile()), selection, output.toFile())
        assertTrue(output.resolve("config/a.json").readText().contains("line\\nvalue"))
    }

    @Test
    fun malformedJsonReportsRelativePath() = withTempDirectory { root ->
        val low = root.resolve("low").createDirectories()
        val high = root.resolve("high").createDirectories()
        writeJson(low, "config/bad.json", "{ invalid")
        writeJson(high, "config/ok.json", "{ \"ok\": true }")

        val error = runCatching {
            ModMergeEngine().merge(listOf(low.toFile(), high.toFile()), selection, root.resolve("output").toFile())
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("config/bad.json"))
    }

    @Test
    fun generatedInfoMarksNoBaseMode() = withTempDirectory { root ->
        val low = root.resolve("low").createDirectories()
        val high = root.resolve("high").createDirectories()
        writeJson(low, "config/a.json", "{ \"a\": 1 }")
        writeJson(high, "config/b.json", "{ \"b\": 2 }")
        val output = root.resolve("output")

        ModMergeEngine().merge(listOf(low.toFile(), high.toFile()), selection, output.toFile())

        val info = output.resolve("Info.json").readText()
        assertTrue(info.contains("no-base-json-overlay"))
        assertFalse(info.contains("base JSON"))
    }

    private fun writeJson(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun <T> withTempDirectory(block: (Path) -> T): T {
        val path = createTempDirectory("merge-engine-test")
        return try {
            block(path)
        } finally {
            path.toFile().deleteRecursively()
        }
    }
}
