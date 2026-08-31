package com.gametree.sultan.pd.mod;

import android.app.Activity;
import android.os.Bundle;

/**
 * 无界面跳板：Manager 在前台显式启动它，只为把游戏包从 stopped 状态里拉出来，
 * 并顺带拉起 :modstorage 进程（该进程的 Provider 会随进程启动发布），随后立即
 * 结束。游戏主进程与游戏界面都不会出现。
 *
 * <p>不校验调用方：本 Activity 不暴露任何数据或操作，唯一效果是进程启动，而
 * 任何应用本来就能以其他方式（如 provider 冷启动）造成同样的进程启动；签名级
 * 自定义权限在本方案中不可行——loader split 与游戏共用设备本地签名，而 Manager
 * 使用发布签名，两者天然不同签名。真正的安全边界在 {@link ModStorageProvider}
 * 的 revision 门与调用方证书钉扎。
 *
 * <p>{@link android.R.style#Theme_NoDisplay} 要求 Activity 在 onResume 完成前
 * finish()，因此这里不能做任何耗时工作。
 */
public final class ModServiceKickstartActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
