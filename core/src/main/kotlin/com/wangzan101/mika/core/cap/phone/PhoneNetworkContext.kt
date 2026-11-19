package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.net.PhoneNetworkPort
import com.wangzan101.mika.core.tool.ToolContext

/**
 * 网络抓包端口和它返回的模型定义在 `core/net`:它们是**中性类型**——界面状态
 * ([com.wangzan101.mika.core.uistate.ScreenPort])要把端口交给工具层,而核心的界面状态不许认识能力包(`cap/` 下那些)。
 * 这里只留工具上下文里的那个入口。
 */

/** 注入到上下文中的网络端口 Key。 */
internal val PHONE_NETWORK_KEY = CapabilityKey<PhoneNetworkPort>("phone_network")

/** 工具上下文快捷访问：优先从 ToolContext 提取，降级从 ScreenPort.network 提取。 */
internal val ToolContext.network: PhoneNetworkPort?
    get() = this[PHONE_NETWORK_KEY] ?: phone.screen.network
