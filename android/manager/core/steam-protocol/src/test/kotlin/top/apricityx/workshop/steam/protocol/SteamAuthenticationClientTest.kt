package top.apricityx.workshop.steam.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.apricityx.workshop.steam.proto.CAuthentication_GetPasswordRSAPublicKey_Response
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

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

    @Test
    fun `password at RSA PKCS1 capacity is encrypted`() {
        val publicKey = rsaPublicKey()
        val password = "a".repeat(117)

        val encrypted = encryptPassword(password, publicKey)

        assertTrue(encrypted.isNotBlank())
    }

    @Test
    fun `password beyond RSA PKCS1 capacity reports a safe login error`() {
        val publicKey = rsaPublicKey()
        val password = "a".repeat(118)

        val capacityError = assertThrows(SteamPasswordEncryptionCapacityException::class.java) {
            encryptPassword(password, publicKey)
        }
        val authenticationError = capacityError.asAuthenticationException(
            prefix = "Steam 登录失败",
            operation = SteamAuthenticationOperation.BeginSession,
        )

        assertEquals(118, capacityError.passwordBytes)
        assertEquals(117, capacityError.maximumPasswordBytes)
        assertEquals(SteamAuthenticationOperation.BeginSession, authenticationError.operation)
        assertTrue(authenticationError.message!!.contains("Steam 密码过长"))
        assertFalse(authenticationError.message!!.contains(password))
    }

    @Test
    fun `password capacity uses UTF8 byte length`() {
        val publicKey = rsaPublicKey()
        val password = "密".repeat(40)

        val error = assertThrows(SteamPasswordEncryptionCapacityException::class.java) {
            encryptPassword(password, publicKey)
        }

        assertEquals(120, error.passwordBytes)
        assertEquals(117, error.maximumPasswordBytes)
    }

    private fun rsaPublicKey(): CAuthentication_GetPasswordRSAPublicKey_Response {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(1024)
        val publicKey = generator.generateKeyPair().public as RSAPublicKey
        return CAuthentication_GetPasswordRSAPublicKey_Response.newBuilder()
            .setPublickeyMod(publicKey.modulus.toString(16))
            .setPublickeyExp(publicKey.publicExponent.toString(16).padStartEven())
            .build()
    }

    private fun String.padStartEven(): String =
        if (length % 2 == 0) this else "0$this"
}
