package com.sultansgame.modmanager.platform.game

import com.sultansgame.modmanager.model.GameModSyncAvailability
import com.sultansgame.modmanager.model.GameModSyncFailureCode
import com.sultansgame.modmanager.model.GameModSyncStatus
import com.sultansgame.modmanager.model.GameSaveAvailability
import com.sultansgame.modmanager.model.GameSaveFailureCode
import com.sultansgame.modmanager.model.GameSaveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModServiceKickstartPolicyTest {
    private fun modStatus(availability: GameModSyncAvailability) =
        GameModSyncStatus(availability, failureCode = GameModSyncFailureCode.None)

    private fun saveStatus(availability: GameSaveAvailability) =
        GameSaveStatus(availability, failureCode = GameSaveFailureCode.None)

    @Test
    fun `mod kickstart is required only for activation required`() {
        GameModSyncAvailability.entries.forEach { availability ->
            val required = ModServiceKickstartPolicy.requiredFor(modStatus(availability))
            assertEquals(availability == GameModSyncAvailability.ActivationRequired, required)
        }
    }

    @Test
    fun `save kickstart is required only for activation required`() {
        GameSaveAvailability.entries.forEach { availability ->
            val required = ModServiceKickstartPolicy.requiredFor(saveStatus(availability))
            assertEquals(availability == GameSaveAvailability.ActivationRequired, required)
        }
    }

    @Test
    fun `cooldown is zero when never failed`() {
        assertEquals(0L, ModServiceKickstartPolicy.cooldownRemainingMs(0L, nowMs = 123_456L))
        assertEquals(0L, ModServiceKickstartPolicy.cooldownRemainingMs(-1L, nowMs = 123_456L))
    }

    @Test
    fun `cooldown is positive inside the window and zero after it expires`() {
        val failedAt = 100_000L
        val inside = ModServiceKickstartPolicy.cooldownRemainingMs(failedAt, nowMs = failedAt + 1L)
        assertTrue(inside > 0L)
        assertEquals(
            ModServiceKickstartPolicy.FAILURE_COOLDOWN_MS - 1L,
            inside,
        )
        assertEquals(
            0L,
            ModServiceKickstartPolicy.cooldownRemainingMs(
                failedAt,
                nowMs = failedAt + ModServiceKickstartPolicy.FAILURE_COOLDOWN_MS,
            ),
        )
    }

    @Test
    fun `cooldown never goes negative`() {
        val remaining = ModServiceKickstartPolicy.cooldownRemainingMs(
            100_000L,
            nowMs = 100_000L + ModServiceKickstartPolicy.FAILURE_COOLDOWN_MS + 5_000L,
        )
        assertEquals(0L, remaining)
        assertFalse(remaining < 0L)
    }
}
