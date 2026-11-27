package com.wangzan101.mika.core.cap.phone

import com.agent.use.flow.AppFlow
import com.agent.use.flow.FlowHit
import com.agent.use.flow.FlowHost
import com.agent.use.flow.FlowRunner
import com.agent.use.flow.FlowSelector
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.SubActions
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.objectArg
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.uistate.session
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 跑插件包里的应用脚本。
 *
 * 脚本来自 `plugin.json` 的 `flows`（见 [FlowPacks]），由 [FlowRunner] 解释；
 * 这个工具只做三件事：挑脚本、把参数填进去、把每一步落到和手点一样的手机工具上。
 *
 * 为什么值得多一层：起点这套「入口 → 等广告 → 点 CTA → 按名字关掉 → 领奖」换个 App
 * 就变一次文案，写死在 Kotlin 里意味着每次都要发版。放进插件包后，现场改一个 JSON
 * 就能试，脚本坏了也只坏一个 App。
 *
 * 落指仍然经 `SubActions`：取消、屏幕守卫、动作记录和单步操作完全一致，脚本本身不碰界面。
 */
internal object PhoneRunFlow : PhoneTool {
    override val verb = Verb.Collect
    override val risk = Risk.Write
    override val composite = true
    override val label = "跑应用脚本"
    override val yieldsToSteer = true

    override val def = ToolDef(
        "phone_run_flow",
        Prompts.tool("phone_run_flow"),
        mapOf(
            "flow" to str("Flow id from the app's plugin package. Empty: the only flow matching the current app."),
            "params" to objectArg("Flow parameters as a JSON object, e.g. {\"rounds\":2}. Keys the script declares."),
        ),
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly {
        try {
            run(a, ctx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 脚本是外部数据，解释器里任何一处初始化失败都不该把整个 App 带走。
            failure(t.message ?: t::class.simpleName.orEmpty())
        }
    }

    private suspend fun run(a: Args, ctx: ToolContext): ToolExecution {
        val sub = ctx.sub ?: return failure("actions_unavailable")
        val snapshot = FlowPacks.load(ctx.phone.screen.flowPackRoots)
        if (snapshot.flows.isEmpty()) {
            return ToolExecution(buildJsonObject {
                put("ok", false)
                put("error", "no_flows")
                put("packs", snapshot.packs)
                put("parseErrors", JsonArray(snapshot.errors.map(::JsonPrimitive)))
            })
        }

        val host = Host(sub, ctx)
        val app = host.foreground()
        val wanted = a.str("flow", max = 80)?.trim()
        val flow = if (wanted.isNullOrEmpty()) {
            val matching = snapshot.flows.filter { it.matchesPackage(app) }
            when {
                matching.size == 1 -> matching.single()
                matching.isEmpty() -> return ToolExecution(buildJsonObject {
                    put("ok", false)
                    put("error", "no_flow_for_app")
                    put("app", app)
                    put("flows", JsonArray(snapshot.flows.map { JsonPrimitive(it.id) }))
                })
                else -> return ToolExecution(buildJsonObject {
                    put("ok", false)
                    put("error", "flow_ambiguous")
                    put("app", app)
                    put("flows", JsonArray(matching.map { JsonPrimitive(it.id) }))
                })
            }
        } else {
            snapshot.flows.firstOrNull { it.id.equals(wanted, ignoreCase = true) }
                ?: return ToolExecution(buildJsonObject {
                    put("ok", false)
                    put("error", "flow_not_found")
                    put("flow", wanted)
                    put("flows", JsonArray(snapshot.flows.map { JsonPrimitive(it.id) }))
                })
        }

        val params = parseParams(a.obj("params"))
        val result = FlowRunner(host).run(flow, params)
        return ToolExecution(buildJsonObject {
            put("ok", result.ok)
            put("flow", flow.id)
            put("pack", flow.packId)
            put("stopped", result.stopped)
            put("steps", result.steps)
            if (host.notes.isNotEmpty()) put("log", JsonArray(host.notes.map(::JsonPrimitive)))
            put("trace", buildJsonArray {
                result.trace.forEach { t ->
                    add(buildJsonObject {
                        put("round", t.round)
                        put("op", t.op)
                        put("on", t.on)
                        put("ok", t.ok)
                    })
                }
            })
        })
    }

    /** 参数值一律按标量读：脚本里只做占位符替换，不需要嵌套结构。 */
    private fun parseParams(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return obj.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
        }.toMap()
    }

    private fun failure(error: String) = ToolExecution(buildJsonObject {
        put("ok", false)
        put("error", error)
    })

    /**
     * 把脚本的六个动作落到手机工具上。
     *
     * 读屏走 `phone_query`（无障碍，带 ref）和 `phone_observe`（OCR 行，只有坐标）两条，
     * 和 `phone_watch_reward_videos` 用的是同一套——差别只在「谁决定点哪里」：那边是 Kotlin，
     * 这边是插件包里的 JSON。
     */
    private class Host(
        private val sub: SubActions,
        private val ctx: ToolContext,
    ) : FlowHost {

        private var n = 0

        override suspend fun find(selector: FlowSelector): List<FlowHit> =
            if (selector.source == FlowSelector.Source.TEXT) textHits(selector) else a11yHits(selector)

        override suspend fun tap(hit: FlowHit): Boolean {
            // 先按坐标落指：福利中心这种整页 H5 的按钮，无障碍的点击动作只换来一个按下的
            // 涟漪，JS 的 click 不触发（真机上点「去完成」毫无反应，广告永远不出来）。
            // 两种树给的都是千分比——无障碍是从 bounds 折算的，OCR 本来就是——所以和手指同路。
            val (x, y) = hit.center
            if (x in 1..999 && y in 1..999) return tapAt(x, y)
            // 连坐标都没有的命中（理论上不会出现）：退回按编号点
            val ref = hit.ref ?: return false
            val args = buildJsonObject {
                put("ref", ref)
                hit.snapshotId?.let { put("snapshotId", it) }
            }
            return call("phone_tap", args.toString()) != null
        }

        override suspend fun tapAt(x: Int, y: Int): Boolean {
            val point = JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y)))
            val args = buildJsonObject {
                put("paths", JsonArray(listOf(JsonArray(listOf(point, point)))))
                put("durationMs", 80)
            }
            return call("phone_gesture", args.toString()) != null
        }

        override suspend fun back(): Boolean = call("phone_back", "{}") != null

        override suspend fun foreground(): String? {
            val content = call("phone_diagnose", "{}") ?: return null
            return (content["currentApp"] as? JsonPrimitive)?.contentOrNull
        }

        /** 脚本里的 `log` 只进回执，不打断界面。 */
        val notes = mutableListOf<String>()

        override suspend fun log(text: String) {
            if (text.isNotBlank()) notes += text
        }

        private suspend fun a11yHits(selector: FlowSelector): List<FlowHit> {
            val texts = selector.texts.ifEmpty { listOf("") }
            for (text in texts) {
                val args = buildJsonObject {
                    if (text.isNotEmpty()) put("text", text)
                    selector.role?.let { put("role", it) }
                    selector.id?.let { put("resourceId", it) }
                    put("limit", 30)
                }
                val content = call("phone_query", args.toString()) ?: continue
                val hits = parseMatches(content)
                if (hits.isNotEmpty()) return hits
            }
            return emptyList()
        }

        private suspend fun textHits(selector: FlowSelector): List<FlowHit> {
            val content = call("phone_observe", "{}") ?: return emptyList()
            val words = content["visibleText"] as? JsonArray ?: return emptyList()
            val lines = words.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val center = obj["center"] as? JsonArray ?: return@mapNotNull null
                val x = (center.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val y = (center.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                FlowHit(name = name, center = x to y)
            }
            val keys = selector.texts
            if (keys.isEmpty()) return lines
            return lines.filter { line -> keys.any { line.name.contains(it, ignoreCase = true) } }
        }

        private fun parseMatches(content: JsonObject): List<FlowHit> {
            val snapshotId = (content["snapshotId"] as? JsonPrimitive)?.contentOrNull
            val matches = content["matches"] as? JsonArray ?: return emptyList()
            return matches.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val ref = ((obj["actionRef"] ?: obj["ref"]) as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                val name = ((obj["name"] ?: obj["text"]) as? JsonPrimitive)?.contentOrNull.orEmpty()
                val bounds = (obj["bounds"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    ?: emptyList()
                if (ref == null && bounds.size < 4) return@mapNotNull null
                val center = if (bounds.size >= 4) {
                    ((bounds[0] + bounds[2]) / 2) to ((bounds[1] + bounds[3]) / 2)
                } else 0 to 0
                FlowHit(
                    name = name,
                    ref = ref,
                    snapshotId = if (ref != null) snapshotId else null,
                    bounds = bounds,
                    center = center,
                )
            }
        }

        private suspend fun call(tool: String, args: String): JsonObject? {
            val result = runCatching {
                sub.run(Part.ToolCall("${ctx.callId}.flow${n++}", tool, args))
            }.getOrNull() ?: return null
            val content = result.content
            if (content["ok"] != JsonPrimitive(true)) return null
            return content
        }
    }
}
