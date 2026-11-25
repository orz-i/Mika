package com.wangzan101.mika.shell

import com.agent.use.shell.ShellBrand
import com.agent.use.shell.ShellBranding

/**
 * Mika 的配对与助手品牌：助手入口类、无障碍服务组件、ADB 证书 CN、脚本提示里的应用名，
 * 以及 shell 输出的退出码标记。
 *
 * 迁移到 AAU 之后这些值不再写死在实现里，两个进程都必须安装一次：
 * App 进程走 [com.wangzan101.mika.MikaApp]，助手进程由 app_process 直接起，不经过 Application，
 * 所以在 [com.wangzan101.mika.cap.phone.shell.HelperProcess.main] 的开头单独装。
 *
 * [exitMark] 保持 `__mika_exit=`：App 与助手可能是不同版本（App 升级、旧助手还在跑），
 * 输出标记一旦漂移，旧助手回的退出码就解析不出来。
 */
object MikaShellBrand {
    val value = ShellBrand(
        appName = "Mika",
        helperClass = "com.wangzan101.mika.shell.HelperMain",
        accessibilityService = "com.wangzan101.mika.a11y.MikaAccessibilityService",
        certCommonName = "Mika",
        exitMark = "__mika_exit=",
    )

    fun install() {
        ShellBranding.configure(value)
    }
}
