package com.wangzan101.mika.cap.phone.network

import android.content.Context
import com.agent.use.common.network.plugin.external.BuiltinCapturePluginPackages
import java.io.File

/**
 * 应用插件包在磁盘上的位置。
 *
 * 插件包不只有抓包规则，还能带 `flows` 应用脚本。目录必须在**接上手机之前**就能读——
 * `phone_run_flow` 可能在任何时刻被调用，不能因为还没建屏就说「这个 App 没有脚本」。
 *
 * 内置包从 assets 装到磁盘，安装器和抓包启动时用的是同一个。**每次取都调一次 install**：
 * 它按包里的 `version` 判断，磁盘上那份不旧就跳过，旧了才覆盖。只在目录不存在时装，
 * 会让升级后改了脚本的包永远停在首装那一版（抓包那条路会装，不开抓包就不会）。
 */
object AppPluginPacks {

    fun roots(context: Context): List<File> {
        val app = context.applicationContext
        val builtin = File(app.filesDir, "capture_plugins_builtin")
        runCatching {
            BuiltinCapturePluginPackages.install(
                BuiltinCapturePluginPackages.fromAssets(app.assets),
                builtin,
            )
        }
        return listOf(builtin, File(app.filesDir, "capture_plugins"))
    }
}
