package com.sultansgame.modmanager.platform.game

import com.sultansgame.modmanager.apk.LoaderSplitRevision
import com.sultansgame.modmanager.model.DeviceSigningKeyState

/** 没有 revision entry 的 loader split 一律视为该值参与比较。 */
internal const val PRE_REVISION_LOADER = 0

enum class UnpatchedReason {
    /** 签名不是本机证书，且没有 modloader split：官方原版。 */
    OfficialInstall,

    /** 有 modloader split 但签名不是本机证书：其他设备或工具修补的。 */
    ForeignSigner,

    /** 本机签名但缺少 modloader split。 */
    LoaderSplitMissing,

    /** 本机从未生成设备签名密钥。 */
    DeviceKeyMissing,

    /** 设备签名密钥已丢失，必须先卸载再重修补。 */
    DeviceKeyLost,
}

sealed interface GameReadiness {
    data object Checking : GameReadiness

    data object NotInstalled : GameReadiness

    data class Unpatched(val versionLabel: String, val reason: UnpatchedReason) : GameReadiness

    data class UpgradeRequired(
        val versionLabel: String,
        val installedRevision: Int,
        val expectedRevision: Int,
    ) : GameReadiness

    /** 已修补可用；revision 为 null 表示无法比较，note 说明原因。 */
    data class Ready(
        val versionLabel: String,
        val revision: Int?,
        val note: String? = null,
    ) : GameReadiness

    /** 已安装的 loader 比管理器内嵌的更新：该更新管理器，而不是重修补游戏。 */
    data class ManagerOutdated(
        val versionLabel: String,
        val installedRevision: Int,
        val expectedRevision: Int,
    ) : GameReadiness

    data class ProbeFailed(val reason: String) : GameReadiness
}

internal fun InstalledGameSnapshot.versionLabel(): String =
    versionName?.takeIf(String::isNotBlank)?.let { "$it（$versionCode）" } ?: versionCode.toString()

internal fun evaluateGameReadiness(
    probe: GameProbeResult?,
    deviceCertificateSha256: String?,
    deviceKeyState: DeviceSigningKeyState,
    loaderSplitName: String,
    expectedRevision: LoaderSplitRevision,
    installedRevision: () -> LoaderSplitRevision,
): GameReadiness = when (probe) {
    null -> GameReadiness.Checking
    GameProbeResult.NotInstalled -> GameReadiness.NotInstalled
    is GameProbeResult.Failed -> GameReadiness.ProbeFailed(probe.reason)
    is GameProbeResult.Found -> {
        val snapshot = probe.snapshot
        val versionLabel = snapshot.versionLabel()
        val loaderPresent = loaderSplitName in snapshot.splitNames
        val deviceSigned = deviceCertificateSha256 != null &&
            snapshot.signerDigestsSha256 == setOf(deviceCertificateSha256)
        when {
            deviceKeyState == DeviceSigningKeyState.MissingAfterMigration ->
                GameReadiness.Unpatched(versionLabel, UnpatchedReason.DeviceKeyLost)
            deviceCertificateSha256 == null ->
                GameReadiness.Unpatched(versionLabel, UnpatchedReason.DeviceKeyMissing)
            !deviceSigned -> GameReadiness.Unpatched(
                versionLabel,
                if (loaderPresent) UnpatchedReason.ForeignSigner else UnpatchedReason.OfficialInstall,
            )
            !loaderPresent -> GameReadiness.Unpatched(versionLabel, UnpatchedReason.LoaderSplitMissing)
            else -> compareLoaderRevisions(versionLabel, expectedRevision, installedRevision())
        }
    }
}

private fun compareLoaderRevisions(
    versionLabel: String,
    expected: LoaderSplitRevision,
    installed: LoaderSplitRevision,
): GameReadiness = when {
    expected !is LoaderSplitRevision.Known -> GameReadiness.Ready(
        versionLabel,
        (installed as? LoaderSplitRevision.Known)?.value,
        "无法读取管理器内嵌模板的 loader revision；已跳过版本比较。",
    )
    installed is LoaderSplitRevision.Unreadable -> GameReadiness.Ready(
        versionLabel,
        null,
        "无法读取已安装 Mod 加载器的 revision（${installed.reason}）；已跳过版本比较。",
    )
    else -> {
        val current = (installed as? LoaderSplitRevision.Known)?.value ?: PRE_REVISION_LOADER
        when {
            current < expected.value -> GameReadiness.UpgradeRequired(versionLabel, current, expected.value)
            current > expected.value -> GameReadiness.ManagerOutdated(versionLabel, current, expected.value)
            else -> GameReadiness.Ready(versionLabel, current)
        }
    }
}
