package com.sultansgame.modmanager.platform.saveeditor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The backup store is the only thing standing between a bad edit and a lost
 * playthrough, so these tests pin its ordering, quota, and isolation between
 * save files.
 */
class SaveBackupStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun store(maxPerFile: Int = 10) =
        SaveBackupStore(File(temporaryFolder.root, "save-backups"), maxPerFile)

    @Test fun createRoundTripsContent() {
        val store = store()
        val entry = store.create("76561199017440601", "auto_save.json", """{"round":3}""")
        assertEquals("auto_save.json", entry.fileName)
        assertEquals("""{"round":3}""", store.read(entry))
        assertTrue(entry.createdAtText.matches(Regex("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}""")))
    }

    @Test fun listReturnsNewestFirst() {
        val store = store()
        val first = store.create("1", "auto_save.json", "a")
        val second = store.create("1", "auto_save.json", "b")
        val third = store.create("1", "auto_save.json", "c")
        val listed = store.list("1", "auto_save.json")
        assertEquals(3, listed.size)
        assertEquals(listOf("c", "b", "a"), listed.map { store.read(it) })
        assertTrue(listed[0].createdAt >= listed[1].createdAt)
        assertNotEquals(first.path, second.path)
        assertNotEquals(second.path, third.path)
    }

    @Test fun identicalContentReusesNewestBackup() {
        val store = store()
        val first = store.create("1", "auto_save.json", "same")
        val second = store.create("1", "auto_save.json", "same")
        assertEquals(first.path, second.path)
        assertEquals(1, store.list("1", "auto_save.json").size)
    }

    @Test fun quotaDropsOldestBackups() {
        val store = store(maxPerFile = 3)
        repeat(6) { index -> store.create("1", "auto_save.json", "revision-$index") }
        val listed = store.list("1", "auto_save.json")
        assertEquals(3, listed.size)
        assertEquals(
            listOf("revision-5", "revision-4", "revision-3"),
            listed.map { store.read(it) },
        )
    }

    @Test fun nestedSaveNamesStayInsideTheirOwnDirectory() {
        val store = store()
        val slot = store.create("1", "USERARCHIVE/003.json", "slot")
        store.create("1", "auto_save.json", "auto")
        assertEquals(listOf("slot"), store.list("1", "USERARCHIVE/003.json").map { store.read(it) })
        assertEquals(listOf("auto"), store.list("1", "auto_save.json").map { store.read(it) })
        val root = File(temporaryFolder.root, "save-backups").canonicalFile
        assertTrue(File(slot.path).canonicalFile.startsWith(root))
        // 目录名固定宽度转义，`/` 不会变成子目录，也不会与其它文件名撞车。
        assertEquals("USERARCHIVE%002F003.json", File(slot.path).parentFile!!.name)
    }

    @Test fun usersDoNotShareBackups() {
        val store = store()
        store.create("1", "auto_save.json", "first user")
        store.create("2", "auto_save.json", "second user")
        assertEquals(listOf("first user"), store.list("1", "auto_save.json").map { store.read(it) })
        assertEquals(listOf("second user"), store.list("2", "auto_save.json").map { store.read(it) })
    }

    @Test fun deleteRemovesOnlyThatBackup() {
        val store = store()
        store.create("1", "auto_save.json", "keep")
        val doomed = store.create("1", "auto_save.json", "drop")
        assertTrue(store.delete(doomed))
        assertFalse(store.delete(doomed))
        assertEquals(listOf("keep"), store.list("1", "auto_save.json").map { store.read(it) })
    }

    @Test fun listIsEmptyForUnknownFile() {
        assertTrue(store().list("1", "global.json").isEmpty())
    }

    @Test fun strayTemporaryFilesAreIgnoredAndCleaned() {
        val store = store()
        val entry = store.create("1", "auto_save.json", "real")
        val stray = File(File(entry.path).parentFile, "999.json.tmp")
        stray.writeText("half written")
        assertEquals(1, store.list("1", "auto_save.json").size)
        store.create("1", "auto_save.json", "next")
        assertFalse(stray.exists())
    }
}
