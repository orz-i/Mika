package com.wangzan101.mika.cap.phone.shell.adb

import android.content.Context
import com.agent.use.setup.WirelessAdbLauncher
import com.wangzan101.mika.core.adb.AdbServiceFinder

/**
 * 用系统 mDNS 发现本机 adbd 无线调试端口，委托给 AAU [WirelessAdbLauncher.findLocalAdbPorts]。
 */
class NsdAdbServiceFinder(context: Context) : AdbServiceFinder {
    private val appContext = context.applicationContext ?: context

    override suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int> =
        WirelessAdbLauncher.findLocalAdbPorts(appContext, serviceType, timeoutMs)
}
