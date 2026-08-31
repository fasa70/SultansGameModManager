package com.sultansgame.modmanager.platform.game

import com.sultansgame.modmanager.model.GameModSyncAvailability
import com.sultansgame.modmanager.model.GameModSyncStatus
import com.sultansgame.modmanager.model.GameSaveAvailability
import com.sultansgame.modmanager.model.GameSaveStatus

/**
 * 跳板冷启动的纯策略：全部时间与判定不依赖 Android，便于 JVM 单测。
 * 通用重试层（另一工作流）应组合本策略与 [ModServiceKickstarter]，而不是
 * 复制这些常量。
 */
internal object ModServiceKickstartPolicy {
    /** 跳板启动后轮询 provider 可达性的间隔。 */
    const val POLL_INTERVAL_MS = 500L

    /** 轮询总时限；stopped 一旦清除，provider 调用本身能拉起进程，轮询自愈。 */
    const val POLL_TIMEOUT_MS = 10_000L

    /** 等待前台 Activity 真正执行 startActivity 的确认时限；被退到后台时事件会等到下一次 onStart。 */
    const val LAUNCH_ACK_TIMEOUT_MS = 10_000L

    /** 刚提交安装后的短暂窗口里 PackageManager 可能还没登记新组件。 */
    const val TRAMPOLINE_RESOLVE_ATTEMPTS = 3
    const val TRAMPOLINE_RESOLVE_RETRY_DELAY_MS = 500L

    /** 失败冷却：避免 onResume 反复触发注定失败的尝试。 */
    const val FAILURE_COOLDOWN_MS = 15_000L

    fun requiredFor(status: GameModSyncStatus): Boolean =
        status.availability == GameModSyncAvailability.ActivationRequired

    fun requiredFor(status: GameSaveStatus): Boolean =
        status.availability == GameSaveAvailability.ActivationRequired

    fun cooldownRemainingMs(lastFailureAtMs: Long, nowMs: Long): Long =
        if (lastFailureAtMs <= 0L) 0L
        else (lastFailureAtMs + FAILURE_COOLDOWN_MS - nowMs).coerceAtLeast(0L)
}
