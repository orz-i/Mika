package com.wangzan101.mika.core.uistate.tools

import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.uistate.LocalIcon
import com.wangzan101.mika.core.uistate.LocalIconRegion
import com.wangzan101.mika.core.uistate.LocalIconResult
import com.wangzan101.mika.core.uistate.Located
import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.uistate.located
import com.wangzan101.mika.core.uistate.locatedInfo
import com.wangzan101.mika.core.uistate.screen
import com.wangzan101.mika.core.uistate.sessions
import kotlinx.serialization.json.put

internal class FindLocalIcon(f: ScreenFamily) : ScreenTool(f, "find_local_icon") {
    override val verb = Verb.FindLocalIcon
    override val risk = Risk.Read
    override val routable = false
    override val yieldsToSteer = true
    override val sandboxCallable = false
    override val def by lazy { ToolDef(name, describe(), mapOf(
        "icon" to str("Glyph: search, close or more (horizontal three dots)."),
        "region" to str("Search area: whole (default), top or bottom third of the virtual screen."),
    ), listOf("icon")) }

    override fun usable(ctx: ToolContext) = ctx.sessions.getValue(family.id).screen.supportsLocalIcons

    override suspend fun execute(a: Args, ctx: ToolContext) = run {
        val label = a.str("icon", max = 16)
        val icon = LocalIcon.entries.firstOrNull { it.wire == label }
            ?: throw ArgError("icon", "must be search, close or more")
        val area = a.str("region", max = 16) ?: "whole"
        val region = LocalIconRegion.entries.firstOrNull { it.wire == area }
            ?: throw ArgError("region", "must be whole, top or bottom")
        val r = if (ctx.screen.isProtected()) LocalIconResult.Miss.Protected else ctx.screen.findLocalIcon(icon, region)
        when (r) {
            is LocalIconResult.Miss -> ok { put("found", false); put("reason", r.wire); put("message", r.message) }
            is LocalIconResult.Found -> {
                val (ref, sid) = ctx.located.putLocal((r.left + r.right) / 2, (r.top + r.bottom) / 2, r.frame)
                ctx.locatedInfo[ref] = Located(icon.wire, null, null, unverified = true)
                ok {
                    put("found", true); put("source", "local_icon"); put("icon", icon.wire)
                    put("ref", ref); put("snapshotId", sid); put("unverified", true)
                }
            }
        }
    }
}
