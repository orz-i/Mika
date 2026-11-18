package com.wangzan101.mika.core.mcp

import com.agent.use.remote.usb.UsbBridge

/**
 * 经 USB 的连接引导。实现在 `aau-remote` 的 `UsbBridge`（连接信息文件 + 电脑上跑的脚本），
 * 这里只固定 Mika 的品牌与对外常量，免得 [com.wangzan101.mika.mcp.McpRuntime] 到处 new 一个实例。
 */
object McpUsb {
    const val INFO_FILE = UsbBridge.INFO_FILE
    const val SCRIPT = UsbBridge.SCRIPT

    private val bridge = UsbBridge()

    fun info(port: Int, token: String, boot: String): String = bridge.info(port, token, boot)

    fun forward(port: Int): String = UsbBridge.forward(port)

    fun script(
        scriptPath: String,
        helperScript: String,
        infoPath: String,
        component: String,
        onAction: String,
        offAction: String,
        port: Int,
    ): String = bridge.script(scriptPath, helperScript, infoPath, component, onAction, offAction, port)
}
