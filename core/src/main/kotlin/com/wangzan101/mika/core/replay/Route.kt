package com.wangzan101.mika.core.replay

import com.agent.use.ui.Anchors
import com.agent.use.ui.RouteStep
import com.agent.use.ui.Selector
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 路线的数据模型（[Selector]、[Anchors]、[RouteStep]、[Route]、编解码与锚点提取）和读屏快照一起在 aau-ui，
 * 这样任何宿主记下的路线都能被同一套代码认出来。这里只留 Mika 自己的两件工具层的事。
 */
typealias Selector = com.agent.use.ui.Selector
typealias Anchors = com.agent.use.ui.Anchors
typealias RouteStep = com.agent.use.ui.RouteStep
typealias Route = com.agent.use.ui.Route

/**
 * 改名前记下的路线:步骤里的旧工具名换成现在能记进路线的那个工具(几块屏都有的动词,取能回放的那一套)。
 * 现在的名字不动;认不出的留着,回放时按「不是可回放的动作」停下。
 */
fun Route.withCurrentToolNames(legacy: LegacyNames, tools: ToolIndex): Route {
    if (steps.all { tools[it.tool] != null }) return this
    return copy(steps = steps.map { st ->
        if (tools[st.tool] != null) st else legacy.resolve(st.tool) { tools[it]?.routable == true }?.let { st.copy(tool = it) } ?: st
    })
}

/** 生效的写操作:ok 且界面变了,或输入回读一致;启动 App、打开链接成功即算(端口已等到目标在前台)。 */
fun effectiveWrite(verb: Verb?, result: JsonObject): Boolean {
    if (result["ok"] != JsonPrimitive(true)) return false
    return result["changed"] == JsonPrimitive(true) || result["verification"] == JsonPrimitive("match") ||
        (verb in OPENS && result["changed"] != JsonPrimitive(false))
}

/** 打开 App、打开链接:成功就是到了目标那里。 */
internal val OPENS = setOf(Verb.LaunchApp, Verb.OpenLink)
