package top.apricityx.workshop.steam.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamAuthenticationClientTest {
    @Test
    fun `duplicate request has actionable authentication description`() {
        assertEquals(
            "Steam 已收到相同认证请求，正在确认登录结果",
            steamAuthenticationResultDescription(29),
        )
    }

    @Test
    fun `begin auth request includes non-blank remembered guard data`() {
        val request = buildBeginAuthSessionRequest(
            details = SteamAuthSessionDetails(
                username = "account",
                password = "unused",
                guardData = "remembered-guard-data",
            ),
            encryptedPassword = "encrypted",
            encryptionTimestamp = 123L,
        )

        assertTrue(request.hasGuardData())
        assertEquals("remembered-guard-data", request.guardData)
    }

    @Test
    fun `begin auth request omits blank remembered guard data`() {
        val request = buildBeginAuthSessionRequest(
            details = SteamAuthSessionDetails(
                username = "account",
                password = "unused",
                guardData = "   ",
            ),
            encryptedPassword = "encrypted",
            encryptionTimestamp = 123L,
        )

        assertFalse(request.hasGuardData())
    }
}
