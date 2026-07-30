package com.sultansgame.modmanager.platform.auth

import android.content.Context
import com.sultansgame.modmanager.model.SteamAuthState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAuthenticationClient
import top.apricityx.workshop.steam.protocol.SteamAuthSessionDetails
import top.apricityx.workshop.steam.protocol.SteamCredentialAuthSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamGuardChallengeType
import top.apricityx.workshop.steam.protocol.newDefaultOkHttpClient

/**
 * Steam credential authentication adapted from Workshop-Native's Guard flow.
 * Only code-based confirmations can be completed locally; Steam App/email-link
 * confirmations deliberately return an actionable error instead of polling forever.
 */
class SteamCmAuthProvider(context: Context) : SteamAuthProvider {
    private val sessionStore = EncryptedSteamSessionStore(context)
    private val client = newDefaultOkHttpClient()
    private val authenticationClient = SteamAuthenticationClient(
        directoryClient = SteamDirectoryClient(client),
        sessionFactory = { OkHttpSteamCmSession(client) },
    )
    private val mutableState = MutableStateFlow(loadSavedState())
    private val authMutex = Mutex()
    private var pendingSession: SteamCredentialAuthSession? = null
    private var selectedChallengeType: SteamGuardChallengeType? = null
    private var activeInMemorySession: SteamAccountSession? = null
    private var pendingRememberSession = false

    override fun observeState(): Flow<SteamAuthState> = mutableState

    override suspend fun beginLogin(credentials: SteamCredentials): SteamAuthResult = authMutex.withLock {
        if (credentials.username.isBlank() || credentials.password.isEmpty()) {
            return@withLock failure("请输入 Steam 账号和密码。")
        }
        closePendingSession()
        activeInMemorySession = null
        sessionStore.clear()
        pendingRememberSession = credentials.rememberSession
        mutableState.value = SteamAuthState.SigningIn
        runCatching {
            val pending = authenticationClient.beginAuthSession(
                SteamAuthSessionDetails(
                    username = credentials.username,
                    password = credentials.password,
                    isPersistentSession = credentials.rememberSession,
                ),
            )
            pendingSession = pending
            val codeChallenges = pending.challenges.filter { it.type in CODE_CHALLENGE_TYPES }
            when {
                codeChallenges.isNotEmpty() -> {
                    selectedChallengeType = codeChallenges.first().type
                    val label = codeChallenges.joinToString(" / ") { challengeLabel(it.type) }
                    mutableState.value = SteamAuthState.SteamGuardRequired(label)
                    SteamAuthResult.SteamGuardRequired(label)
                }
                pending.challenges.any { it.type == SteamGuardChallengeType.None } -> completePendingLogin()
                pending.challenges.any { it.type in CONFIRMATION_CHALLENGE_TYPES } -> {
                    failure("当前登录需要在 Steam App 或邮件确认页中批准。本应用仅支持输入 Steam Guard 或邮箱验证码；请使用可输入验证码的验证方式后重试。")
                }
                else -> failure("Steam 要求当前版本尚不支持的验证方式。")
            }
        }.getOrElse { failure(it.message ?: "Steam 登录失败。") }
    }

    override suspend fun submitSteamGuard(code: String): SteamAuthResult = authMutex.withLock {
        val pending = pendingSession ?: return@withLock failure("没有等待中的 Steam 登录。")
        val type = selectedChallengeType ?: return@withLock failure("Steam 没有提供可输入的验证码方式。")
        if (code.isBlank()) return@withLock SteamAuthResult.Failed("请输入 Steam Guard 或邮箱验证码。")
        runCatching {
            pending.submitGuardCode(type, code.trim())
            completePendingLogin()
        }.getOrElse { error ->
            // The session remains usable after an invalid code. Do not discard it
            // until Steam reports a terminal error or the user cancels/login changes.
            mutableState.value = SteamAuthState.SteamGuardRequired(challengeLabel(type))
            SteamAuthResult.Failed(error.message ?: "Steam Guard 验证失败，请检查验证码后重试。")
        }
    }

    override suspend fun logout(): SteamAuthResult = authMutex.withLock {
        closePendingSession()
        activeInMemorySession = null
        pendingRememberSession = false
        sessionStore.clear()
        mutableState.value = SteamAuthState.SignedOut
        SteamAuthResult.Cleared
    }

    fun activeSession(): SteamAccountSession? = activeInMemorySession ?: persistentSession()

    fun persistentSession(): SteamAccountSession? = sessionStore.load()?.let(::decodeSession)

    private suspend fun completePendingLogin(): SteamAuthResult {
        val pending = pendingSession ?: return failure("没有等待中的 Steam 登录。")
        return try {
            val result = pending.awaitResult(MAXIMUM_AUTH_POLL_ATTEMPTS)
            val session = SteamAccountSession(
                accountName = result.accountName,
                steamId = result.steamId,
                refreshToken = result.refreshToken,
            )
            activeInMemorySession = session
            if (pendingRememberSession) {
                sessionStore.save(encodeSession(session))
            } else {
                sessionStore.clear()
            }
            mutableState.value = SteamAuthState.SignedIn(session.accountName, session.steamId)
            closePendingSession()
            SteamAuthResult.SignedIn(session.accountName, session.steamId)
        } catch (error: Throwable) {
            // Timeout and code errors return to the input state. A subsequent
            // submit can reuse the credential session while Steam keeps it alive.
            if (selectedChallengeType != null) {
                mutableState.value = SteamAuthState.SteamGuardRequired(challengeLabel(selectedChallengeType!!))
                SteamAuthResult.Failed(error.message ?: "Steam 验证尚未完成，请重试。")
            } else {
                failure(error.message ?: "Steam 登录失败。")
            }
        }
    }

    private fun loadSavedState(): SteamAuthState = activeSession()
        ?.let { SteamAuthState.SignedIn(it.accountName, it.steamId) }
        ?: SteamAuthState.SignedOut

    private fun failure(reason: String): SteamAuthResult {
        closePendingSession()
        activeInMemorySession = null
        pendingRememberSession = false
        sessionStore.clear()
        mutableState.value = SteamAuthState.SignedOut
        return SteamAuthResult.Failed(reason)
    }

    private fun closePendingSession() {
        pendingSession?.close()
        pendingSession = null
        selectedChallengeType = null
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

    private fun challengeLabel(type: SteamGuardChallengeType): String = when (type) {
        SteamGuardChallengeType.DeviceCode -> "Steam Guard 动态验证码"
        SteamGuardChallengeType.EmailCode -> "Steam 邮箱验证码"
        else -> type.name
    }

    private companion object {
        const val MAXIMUM_AUTH_POLL_ATTEMPTS = 30
        val CODE_CHALLENGE_TYPES = setOf(
            SteamGuardChallengeType.DeviceCode,
            SteamGuardChallengeType.EmailCode,
        )
        val CONFIRMATION_CHALLENGE_TYPES = setOf(
            SteamGuardChallengeType.DeviceConfirmation,
            SteamGuardChallengeType.EmailConfirmation,
        )
    }
}
