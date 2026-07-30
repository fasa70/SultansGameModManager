package com.sultansgame.modmanager.platform.auth

import android.content.Context
import com.sultansgame.modmanager.model.SteamAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAuthenticationClient
import top.apricityx.workshop.steam.protocol.SteamAuthenticationException
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
    private var guardVerificationInProgress = false

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
        try {
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failure(error.message ?: "Steam 登录失败。")
        }
    }

    override suspend fun submitSteamGuard(code: String): SteamAuthResult = authMutex.withLock {
        val pending = pendingSession ?: return@withLock failure("没有等待中的 Steam 登录。")
        val type = selectedChallengeType ?: return@withLock failure("Steam 没有提供可输入的验证码方式。")
        if (guardVerificationInProgress) {
            return@withLock SteamAuthResult.Failed("正在确认 Steam 登录状态，请勿重复提交验证码。")
        }
        if (code.isBlank()) return@withLock SteamAuthResult.Failed("请输入 Steam Guard 或邮箱验证码。")

        guardVerificationInProgress = true
        val challenge = challengeLabel(type)
        mutableState.value = SteamAuthState.VerifyingSteamGuard(challenge)
        try {
            try {
                pending.submitGuardCode(type, code.trim())
            } catch (error: CancellationException) {
                throw error
            } catch (error: SteamAuthenticationException) {
                when {
                    error.resultCode in INVALID_GUARD_CODE_RESULTS -> {
                        mutableState.value = SteamAuthState.SteamGuardRequired(challenge)
                        return@withLock SteamAuthResult.Failed(error.message ?: "Steam Guard 验证码错误，请重新输入。")
                    }
                    error.resultCode != DUPLICATE_REQUEST_RESULT && !error.deliveryUncertain -> {
                        return@withLock failure(error.message ?: "提交 Steam Guard 验证码失败。")
                    }
                    // Steam has either already accepted this update or its delivery is
                    // unknown. Poll this exact credential session; never submit again.
                }
            }
            completePendingLogin()
        } finally {
            guardVerificationInProgress = false
        }
    }

    override suspend fun checkPendingLogin(): SteamAuthResult = authMutex.withLock {
        val type = selectedChallengeType ?: return@withLock failure("没有等待中的 Steam 登录。")
        if (guardVerificationInProgress) {
            return@withLock SteamAuthResult.Failed("正在确认 Steam 登录状态，请稍候。")
        }
        guardVerificationInProgress = true
        mutableState.value = SteamAuthState.VerifyingSteamGuard(challengeLabel(type))
        try {
            completePendingLogin()
        } finally {
            guardVerificationInProgress = false
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val challenge = selectedChallengeType?.let(::challengeLabel)
            if (challenge != null) {
                mutableState.value = SteamAuthState.SteamAuthStatusUnknown(challenge)
                SteamAuthResult.Failed(
                    "Steam 验证码可能已被接收，但暂时无法确认登录结果。请继续检查登录状态，不要重复提交验证码。",
                )
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
        guardVerificationInProgress = false
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
        const val DUPLICATE_REQUEST_RESULT = 29
        val INVALID_GUARD_CODE_RESULTS = setOf(65, 88)
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
