package com.sultansgame.modmanager.platform.auth

import android.content.Context
import com.sultansgame.modmanager.model.SteamAuthState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAuthenticationClient
import top.apricityx.workshop.steam.protocol.SteamAuthSessionDetails
import top.apricityx.workshop.steam.protocol.SteamCredentialAuthSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamGuardChallengeType
import top.apricityx.workshop.steam.protocol.newDefaultOkHttpClient

class SteamCmAuthProvider(context: Context) : SteamAuthProvider {
    private val sessionStore = EncryptedSteamSessionStore(context)
    private val client = newDefaultOkHttpClient()
    private val authenticationClient = SteamAuthenticationClient(
        directoryClient = SteamDirectoryClient(client),
        sessionFactory = { OkHttpSteamCmSession(client) },
    )
    private val mutableState = MutableStateFlow(loadSavedState())
    private var pendingSession: SteamCredentialAuthSession? = null

    override fun observeState(): Flow<SteamAuthState> = mutableState

    override suspend fun beginLogin(credentials: SteamCredentials): SteamAuthResult {
        if (credentials.username.isBlank() || credentials.password.isEmpty()) {
            return failure("请输入 Steam 账号和密码。")
        }
        pendingSession?.close()
        mutableState.value = SteamAuthState.SigningIn
        return runCatching {
            val pending = authenticationClient.beginAuthSession(
                SteamAuthSessionDetails(
                    username = credentials.username,
                    password = credentials.password,
                    isPersistentSession = true,
                ),
            )
            pendingSession = pending
            val challenge = pending.challenges.firstOrNull()
            when (challenge?.type) {
                null, SteamGuardChallengeType.None -> completePendingLogin()
                SteamGuardChallengeType.EmailCode, SteamGuardChallengeType.DeviceCode -> {
                    mutableState.value = SteamAuthState.SteamGuardRequired(challenge.type.name)
                    SteamAuthResult.SteamGuardRequired(challenge.type.name)
                }
                SteamGuardChallengeType.DeviceConfirmation, SteamGuardChallengeType.EmailConfirmation -> {
                    mutableState.value = SteamAuthState.AwaitingConfirmation(challenge.type.name)
                    SteamAuthResult.AwaitingConfirmation(challenge.type.name)
                }
                else -> failure("Steam 要求当前版本尚不支持的验证方式：${challenge.type.name}。")
            }
        }.getOrElse { failure(it.message ?: "Steam 登录失败。") }
    }

    override suspend fun submitSteamGuard(code: String): SteamAuthResult {
        val pending = pendingSession ?: return failure("没有等待中的 Steam 登录。")
        if (code.isBlank()) return failure("请输入 Steam Guard 验证码。")
        return runCatching {
            val challenge = pending.challenges.firstOrNull()
                ?: return@runCatching failure("Steam 没有提供验证方式。")
            pending.submitGuardCode(challenge.type, code)
            completePendingLogin()
        }.getOrElse { failure(it.message ?: "Steam Guard 验证失败。") }
    }

    suspend fun awaitConfirmation(): SteamAuthResult {
        if (pendingSession == null) return failure("没有等待中的 Steam 登录。")
        return runCatching { completePendingLogin() }
            .getOrElse { failure(it.message ?: "Steam 确认失败。") }
    }

    fun activeSession(): SteamAccountSession? = sessionStore.load()?.let(::decodeSession)

    override suspend fun logout(): SteamAuthResult {
        pendingSession?.close()
        pendingSession = null
        sessionStore.clear()
        mutableState.value = SteamAuthState.SignedOut
        return SteamAuthResult.Cleared
    }

    private suspend fun completePendingLogin(): SteamAuthResult {
        val pending = pendingSession ?: return failure("没有等待中的 Steam 登录。")
        return try {
            val result = pending.awaitResult()
            val session = SteamAccountSession(
                accountName = result.accountName,
                steamId = result.steamId,
                refreshToken = result.refreshToken,
            )
            sessionStore.save(encodeSession(session))
            mutableState.value = SteamAuthState.SignedIn(session.accountName, session.steamId)
            SteamAuthResult.SignedIn(session.accountName, session.steamId)
        } finally {
            pending.close()
            pendingSession = null
        }
    }

    private fun loadSavedState(): SteamAuthState = activeSession()
        ?.let { SteamAuthState.SignedIn(it.accountName, it.steamId) }
        ?: SteamAuthState.SignedOut

    private fun failure(reason: String): SteamAuthResult {
        pendingSession?.close()
        pendingSession = null
        mutableState.value = SteamAuthState.SignedOut
        return SteamAuthResult.Failed(reason)
    }

    private fun encodeSession(session: SteamAccountSession): ByteArray = listOf(
        session.accountName,
        session.steamId.toString(),
        session.refreshToken,
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun decodeSession(blob: ByteArray): SteamAccountSession? = runCatching {
        val fields = blob.toString(Charsets.UTF_8).split('\n', limit = 3)
        require(fields.size == 3 && fields[0].isNotBlank() && fields[2].isNotBlank())
        SteamAccountSession(
            accountName = fields[0],
            steamId = fields[1].toLong(),
            refreshToken = fields[2],
        )
    }.getOrNull()
}
