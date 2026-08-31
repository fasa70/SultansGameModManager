package com.sultansgame.modmanager.platform.game

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.sultansgame.modmanager.model.GAME_MOD_STORAGE_KICKSTART_ACTIVITY

/**
 * 把游戏包从 stopped 状态唤醒并拉起 :modstorage 进程，而不打开游戏界面。
 *
 * loader split 内置一个 Theme.NoDisplay 的跳板 Activity（声明在 :modstorage 进程）：
 * 前台 Activity start 会清除包的 FLAG_STOPPED 并让系统随进程发布该进程的全部
 * Provider。MIUI 等厂商 ROM 只拦截跨应用的后台唤醒（Provider/Service/Broadcast），
 * 不拦截前台 Activity start，所以这条路在所有设备上都通。
 *
 * 调用方绝不能给返回的 Intent 加 FLAG_ACTIVITY_NEW_TASK——那会把跳板放进游戏自己
 * 的任务，既可能出现在最近任务里，也可能把游戏任务提到前台。
 */
internal class ModServiceKickstarter(
    private val packageManager: PackageManager,
) {
    /**
     * 解析跳板 Intent；组件不存在（旧 revision 的已修补游戏）、被用户停用，
     * 或声明特征不符（未导出 / 不在 :modstorage 进程）时返回 null。
     */
    fun trampolineIntent(): Intent? {
        val component = ComponentName(GAME_PACKAGE, GAME_MOD_STORAGE_KICKSTART_ACTIVITY)
        val info = try {
            packageManager.getActivityInfo(component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return null
        if (!info.exported) return null
        // 归档解析与安装态对 processName 的规范化不同（缩写 vs 全名），两种都接受。
        if (info.processName != PROCESS_MODSTORAGE && info.processName != "$GAME_PACKAGE$PROCESS_MODSTORAGE") return null
        return Intent().setComponent(component)
    }

    private companion object {
        const val GAME_PACKAGE = "com.gametree.sultan.pd"
        const val PROCESS_MODSTORAGE = ":modstorage"
    }
}
