package com.sultansgame.modmanager.platform.auth

import com.sultansgame.modmanager.model.SteamAuthState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class SteamCredentials(
    val username: String,
    val password: String,
)

interface SteamAuthProvider {
    fun observeState(): Flow<SteamAuthState>
    suspend fun beginLogin(credentials: SteamCredentials): SteamAuthResult
    suspend fun submitSteamGuard(code: String): SteamAuthResult
    suspend fun logout(): SteamAuthResult
}

sealed interface SteamAuthResult {
    data object Cleared : SteamAuthResult
    data class SignedIn(val accountName: String, val steamId: Long) : SteamAuthResult
    data class SteamGuardRequired(val challenge: String) : SteamAuthResult
    data class AwaitingConfirmation(val challenge: String) : SteamAuthResult
    data class Failed(val reason: String) : SteamAuthResult
    data class Unavailable(val reason: String) : SteamAuthResult
}

class UnavailableSteamAuthProvider : SteamAuthProvider {
    override fun observeState(): Flow<SteamAuthState> = flowOf(SteamAuthState.AuthenticationUnavailable)

    override suspend fun beginLogin(credentials: SteamCredentials): SteamAuthResult = unavailable()

    override suspend fun submitSteamGuard(code: String): SteamAuthResult = unavailable()

    override suspend fun logout(): SteamAuthResult = SteamAuthResult.Cleared

    private fun unavailable() = SteamAuthResult.Unavailable(
        "Steam 登录组件尚未可用。",
    )
}
