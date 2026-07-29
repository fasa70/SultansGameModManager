package com.sultansgame.modmanager.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ModStorageModelsTest {
    @Test
    fun `enabled entries encode the requested native load order`() {
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        val snapshot = DeploymentSnapshot(
            revision = UUID.randomUUID().toString(),
            entries = listOf(
                DeploymentEntry(second, second, "Later", enabled = true, order = 20),
                DeploymentEntry(first, first, "First", enabled = true, order = 10),
                DeploymentEntry("c".repeat(64), "c".repeat(64), "Disabled", enabled = false, order = 0),
            ),
            snapshotDigestSha256 = "d".repeat(64),
        )

        assertEquals(listOf("000010--$first", "000020--$second"), snapshot.enabledEntries.map { it.directoryName })
    }
}
