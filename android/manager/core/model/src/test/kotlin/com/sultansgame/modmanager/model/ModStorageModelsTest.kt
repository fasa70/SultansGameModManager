package com.sultansgame.modmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModStorageModelsTest {
    @Test
    fun `manager directory name is stable and does not encode load order`() {
        val cacheKey = "a".repeat(64)
        val item = GameModSyncItem(cacheKey, cacheKey, "Test Mod", syncedToGame = true)

        assertEquals("sgmm-$cacheKey", item.directoryName)
        assertTrue(item.syncedToGame)
    }

    @Test
    fun `directory entries distinguish manager and external mods`() {
        val cacheKey = "b".repeat(64)

        assertTrue(GameModDirectoryEntry("sgmm-$cacheKey", cacheKey).managedByManager)
        assertFalse(GameModDirectoryEntry("manual-mod").managedByManager)
    }
}
