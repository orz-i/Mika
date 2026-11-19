package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.atlas.AppAtlas
import com.wangzan101.mika.core.atlas.AtlasNavigator
import com.wangzan101.mika.core.atlas.AtlasReplayBridge
import com.wangzan101.mika.core.atlas.AtlasShortcut
import com.wangzan101.mika.core.atlas.AtlasShortcutRunner
import com.wangzan101.mika.core.atlas.AtlasStore
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.LinkPolicy
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.SubActions
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.interfaces
import com.wangzan101.mika.core.uistate.session
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * L2 快捷流程执行工具（`phone_run_shortcut`）：
 * 仅在传入 [AtlasStore] 时开放；`routable = false` 避免作为单步被二次录制，
 * 内部每一步均经 `ActionExecutor`（`Origin.Replay`）逐拍校验并执行，遇到确认、守卫或偏离即刻交还模型。
 */
internal object PhoneRunShortcut : PhoneTool {
    override val verb = Verb.RunShortcut
    override val risk = Risk.Write
    override val composite = true
    override val replaySubActions = true
    override val routable = false
    override val label = "执行快捷流程"
    override val def = ToolDef(
        "phone_run_shortcut",
        Prompts.tool("phone_run_shortcut"),
        mapOf(
            "shortcutId" to str("ID of the saved AtlasShortcut to execute."),
            "package" to str("Optional Android package name owning the shortcut; defaults to the current foreground app."),
            "slots" to buildJsonObject {
                put("type", "array")
                put("description", "Slot values matching {1}, {2}, ... placeholders in order.")
                put("items", buildJsonObject { put("type", "string") })
            },
        ),
        listOf("shortcutId"),
    )

    override fun available(ctx: ToolContext): Boolean = ctx.phone.atlas != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly {
        val store = ctx.phone.atlas ?: return@textOnly failed("atlas_unavailable", recoverable = false)
        val sub = ctx.sub ?: return@textOnly failed("actions_unavailable", recoverable = false)
        val shortcutId = a.str("shortcutId")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ArgError("shortcutId", "required")
        val slots = parseSlots(a.o["slots"])

        val pkgArg = a.str("package")?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = resolveShortcut(store, ctx, shortcutId, pkgArg)
            ?: return@textOnly failed("shortcut_not_found", message = "Shortcut '$shortcutId' not found.")
        val (pkg, appAtlas, shortcut) = resolved
        if (shortcut.disabled) {
            return@textOnly failed("shortcut_disabled", message = "Shortcut '$shortcutId' is disabled.")
        }
        if (slots.size != shortcut.slots) {
            return@textOnly failed(
                "slot_count_mismatch",
                message = "Shortcut '$shortcutId' expects ${shortcut.slots} slot values, got ${slots.size}.",
            )
        }

        val bridge = createBridge(sub, ctx, prefix = "${ctx.callId}.sc")
        val runner = AtlasShortcutRunner.forBridge(bridge)
        val nowMs = System.currentTimeMillis()
        val outcome = runner.run(shortcut, slots, appAtlas, callIdPrefix = "${ctx.callId}.step")
        val finalScreen = readFinalScreen(sub, ctx, prefix = "${ctx.callId}.end")

        return@textOnly when (outcome) {
            is AtlasShortcutRunner.Outcome.Completed -> {
                store.update(pkg) { cur -> cur?.recordShortcutOutcome(shortcut.id, succeeded = true, nowMs = nowMs) }
                ToolExecution(
                    buildJsonObject {
                        put("ok", true)
                        put("shortcutId", shortcut.id)
                        put("package", pkg)
                        put("completed", outcome.done)
                        put("total", outcome.total)
                        finalScreen?.let { put("screen", it) }
                    },
                )
            }
            is AtlasShortcutRunner.Outcome.StoppedForConfirmation -> ToolExecution(
                buildJsonObject {
                    put("ok", false)
                    put("error", "confirmation_required")
                    put("shortcutId", shortcut.id)
                    put("package", pkg)
                    put("completed", outcome.done)
                    put("total", outcome.total)
                    put("stepTool", outcome.stepTool)
                    put("reason", outcome.reason)
                    put("recoverable", true)
                    put("counts_as_failure", false)
                    finalScreen?.let { put("screen", it) }
                },
            )
            is AtlasShortcutRunner.Outcome.Diverged -> {
                if (outcome.executed || outcome.error in RECORD_MISS_ERRORS) {
                    store.update(pkg) { cur ->
                        cur?.recordShortcutOutcome(shortcut.id, succeeded = false, missStep = outcome.done, nowMs = nowMs)
                    }
                }
                ToolExecution(
                    buildJsonObject {
                        put("ok", false)
                        put("error", "shortcut_diverged")
                        put("shortcutId", shortcut.id)
                        put("package", pkg)
                        put("completed", outcome.done)
                        put("total", outcome.total)
                        put("executed", outcome.executed)
                        put("reason", outcome.reason)
                        outcome.error?.let { put("stepError", it) }
                        if (outcome.seen.isNotBlank()) put("seen", outcome.seen)
                        put("recoverable", true)
                        put("counts_as_failure", false)
                        finalScreen?.let { put("screen", it) }
                    },
                )
            }
        }
    }

    private suspend fun resolveShortcut(
        store: AtlasStore,
        ctx: ToolContext,
        shortcutId: String,
        pkgArg: String?,
    ): Triple<String, AppAtlas, AtlasShortcut>? {
        val currentPkg = pkgArg
            ?: ctx.session.peek()?.packageName
            ?: ctx.session.lastObservedSnapshot?.packageName
            ?: ctx.session.lastSnapshot?.packageName
        if (currentPkg != null) {
            val atlas = store.get(currentPkg)
            val sc = atlas?.findShortcut(shortcutId)
            if (atlas != null && sc != null) return Triple(currentPkg, atlas, sc)
            if (pkgArg != null) return null
        }
        for (pkg in store.listPackages()) {
            val atlas = store.get(pkg) ?: continue
            val sc = atlas.findShortcut(shortcutId) ?: continue
            return Triple(pkg, atlas, sc)
        }
        return null
    }

    private fun parseSlots(raw: kotlinx.serialization.json.JsonElement?): List<String> {
        if (raw == null) return emptyList()
        val arr = raw as? JsonArray ?: throw ArgError("slots", "must be an array of strings")
        return arr.mapIndexed { idx, el ->
            (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw ArgError("slots[$idx]", "must be a string")
        }
    }

    private val RECORD_MISS_ERRORS = setOf("target_not_found", "anchor_mismatch", "entry_mismatch", "no_expected_change")
}

/**
 * L3 自动寻路导航工具（`phone_navigate_to`）：
 * 仅在传入 [AtlasStore] 时开放；基于 [AtlasNavigator] 规划最短可靠路径并经 `Origin.Replay` 逐跳执行与核验。
 */
internal object PhoneNavigateTo : PhoneTool {
    override val verb = Verb.NavigateTo
    override val risk = Risk.Write
    override val composite = true
    override val replaySubActions = true
    override val routable = false
    override val label = "自动寻路导航"
    override val def = ToolDef(
        "phone_navigate_to",
        Prompts.tool("phone_navigate_to"),
        mapOf(
            "target" to str("Target screen title, short activity name, sigId, or anchor text."),
            "package" to str("Optional Android package name; defaults to the current foreground app."),
            "maxHops" to int("Maximum hops to execute, 1-12 (default 8).", 1, 12),
        ),
        listOf("target"),
    )

    override fun available(ctx: ToolContext): Boolean = ctx.phone.atlas != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly {
        val store = ctx.phone.atlas ?: return@textOnly failed("atlas_unavailable", recoverable = false)
        val sub = ctx.sub ?: return@textOnly failed("actions_unavailable", recoverable = false)
        val target = a.str("target", max = 120)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ArgError("target", "required")
        val maxHops = a.int("maxHops", 1, 12) ?: AtlasNavigator.DEFAULT_MAX_HOPS

        val bridge = createBridge(sub, ctx, prefix = "${ctx.callId}.nav")
        val initial = bridge.readScreen()
            ?: return@textOnly failed("screen_unavailable", message = "Current screen could not be read.")
        if (initial.protected) {
            return@textOnly failed("protected_page", message = "Current screen is protected.")
        }
        val pkg = a.str("package")?.trim()?.takeIf { it.isNotEmpty() }
            ?: initial.packageName?.takeIf { store.get(it) != null }
            ?: resolvePackageByTarget(store, target)
            ?: return@textOnly failed("no_app_open", message = "Cannot determine which app '$target' belongs to.")
        val appAtlas = store.get(pkg)
            ?: return@textOnly failed("atlas_not_found", message = "No atlas available for $pkg.")

        val navigator = AtlasNavigator.forBridge(bridge)
        val outcome = navigator.navigate(
            atlas = appAtlas,
            targetQuery = target,
            maxHops = maxHops,
            store = store,
            callIdPrefix = "${ctx.callId}.hop",
            initialSnapshot = initial,
        )
        val finalScreen = readFinalScreen(sub, ctx, prefix = "${ctx.callId}.end")

        return@textOnly when (outcome) {
            is AtlasNavigator.Outcome.Arrived -> ToolExecution(
                buildJsonObject {
                    put("ok", true)
                    put("package", pkg)
                    put("targetScreenId", outcome.targetScreen.sigId)
                    put("targetTitle", outcome.targetScreen.displayTitle())
                    put("hops", outcome.hops)
                    put("alreadyThere", outcome.alreadyThere)
                    finalScreen?.let { put("screen", it) }
                },
            )
            is AtlasNavigator.Outcome.StoppedForConfirmation -> ToolExecution(
                buildJsonObject {
                    put("ok", false)
                    put("error", "confirmation_required")
                    put("package", pkg)
                    put("hopsCompleted", outcome.hopsCompleted)
                    put("totalHops", outcome.totalHops)
                    put("reason", outcome.reason)
                    put("recoverable", true)
                    put("counts_as_failure", false)
                    finalScreen?.let { put("screen", it) }
                },
            )
            is AtlasNavigator.Outcome.Diverged -> ToolExecution(
                buildJsonObject {
                    put("ok", false)
                    put("error", "navigation_diverged")
                    put("package", pkg)
                    put("hopsCompleted", outcome.hopsCompleted)
                    put("totalHops", outcome.totalHops)
                    outcome.expectedScreen?.let { put("expectedScreen", it.displayTitle()) }
                    put("executed", outcome.executed)
                    put("reason", outcome.reason)
                    outcome.error?.let { put("stepError", it) }
                    if (outcome.seen.isNotBlank()) put("seen", outcome.seen)
                    put("recoverable", true)
                    put("counts_as_failure", false)
                    finalScreen?.let { put("screen", it) }
                },
            )
            is AtlasNavigator.Outcome.NoPath -> ToolExecution(
                buildJsonObject {
                    put("ok", false)
                    put("error", "no_path")
                    put("package", pkg)
                    put("reason", outcome.reason)
                    if (outcome.seen.isNotBlank()) put("seen", outcome.seen)
                    put("recoverable", true)
                    put("counts_as_failure", false)
                    finalScreen?.let { put("screen", it) }
                },
            )
        }
    }
}

private fun resolvePackageByTarget(store: AtlasStore, target: String): String? {
    val hits = store.listPackages().filter { pkg ->
        val atlas = store.get(pkg) ?: return@filter false
        AtlasNavigator.resolveTargets(atlas.screens, target).isNotEmpty()
    }
    return hits.singleOrNull()
}

private fun createBridge(sub: SubActions, ctx: ToolContext, prefix: String): AtlasReplayBridge {
    var readSeq = 0
    val listTool = ctx.tools.nameOf(Verb.ListElements, ctx.phone.id) ?: "phone_list_elements"
    val listInfo = ctx.tools[listTool] as? AgentTool
    return AtlasReplayBridge.forSubActions(
        sub = sub,
        infoLookup = { name -> ctx.tools[name] },
        readSnapshotFn = {
            val r = sub.run(Part.ToolCall("$prefix.r${++readSeq}", listTool, "{}"))
            val sid = (r.content["snapshotId"] as? JsonPrimitive)?.contentOrNull
            val snap = sid?.let { ctx.interfaces.snapshotFor(listInfo, JsonObject(mapOf("snapshotId" to JsonPrimitive(it)))) }
            snap ?: if (r.content["protected"] == JsonPrimitive(true)) {
                ScreenSnapshot("protected-${readSeq}", (r.content["currentApp"] as? JsonPrimitive)?.contentOrNull, emptyList(), 0, 0, protected = true)
            } else {
                null
            }
        },
        peekSnapshotFn = { ctx.session.peek() ?: ctx.session.lastObservedSnapshot },
        linkAllowedFn = { action, uri, pkg ->
            LinkPolicy.check(action, uri, pkg) == null && (action != null || (uri to pkg) in ctx.allowedLinks)
        },
    )
}

private suspend fun readFinalScreen(sub: SubActions, ctx: ToolContext, prefix: String): JsonObject? {
    val listTool = ctx.tools.nameOf(Verb.ListElements, ctx.phone.id) ?: "phone_list_elements"
    val res = sub.run(Part.ToolCall("$prefix.final", listTool, "{}"))
    return res.content.takeIf { it["ok"] == JsonPrimitive(true) }
}

private fun failed(
    error: String,
    message: String? = null,
    recoverable: Boolean = true,
): ToolExecution = ToolExecution(
    buildJsonObject {
        put("ok", false)
        put("error", error)
        message?.let { put("message", it) }
        put("recoverable", recoverable)
        put("counts_as_failure", false)
    },
)
