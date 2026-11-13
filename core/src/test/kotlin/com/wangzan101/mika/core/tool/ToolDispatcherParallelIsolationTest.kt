package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.access.AppAccessDenied
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.cap.phone.PhoneCapability
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.uistate.Interfaces
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ToolDispatcherParallelIsolationTest {
    private class SpySurfaceLayer(private val delegate: SurfaceLayer) : SurfaceLayer by delegate {
        val surfaceTouches = AtomicInteger(0)
        override fun enter(id: String?) {
            surfaceTouches.incrementAndGet()
            delegate.enter(id)
        }
        override fun leave(succeeded: Boolean) {
            surfaceTouches.incrementAndGet()
            delegate.leave(succeeded)
        }
        override fun misaimed(tool: AgentTool, args: JsonObject, tools: ToolIndex): ToolExecution? {
            surfaceTouches.incrementAndGet()
            return delegate.misaimed(tool, args, tools)
        }
        override fun skip() {
            surfaceTouches.incrementAndGet()
            delegate.skip()
        }
        override suspend fun around(
            tool: AgentTool,
            args: JsonObject,
            inlineScreen: Boolean,
            body: suspend (reportsEffect: Boolean) -> ToolExecution,
        ): ToolExecution {
            surfaceTouches.incrementAndGet()
            return delegate.around(tool, args, inlineScreen, body)
        }
    }

    @Test
    fun concurrentParallelOffScreenCallsIsolateCallSessionAndSkipSurfaceLayer() = runBlocking(Dispatchers.Default) {
        val phoneCap = PhoneCapability(FakeScreen(), deepLinks = false)
        lateinit var spySurface: SpySurfaceLayer
        val parallelProbe = object : AgentTool {
            override val def = ToolDef(
                name = "parallel_probe",
                description = "test parallel probe",
                props = mapOf("wait" to buildJsonObject { put("type", "integer") }),
            )
            override val verb = Verb.ReadFile
            override val offScreen = true
            override val risk = Risk.Read
            override val concurrency = Concurrency.ParallelSafe
            override val logged = true
            override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> =
                listOf(Observation(ObsKind.Read, "obs-${r.content["idAfter"]?.jsonPrimitive?.content}"))

            override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
                val idBefore = ctx.callId
                val wait = a.int("wait", 1, 1000) ?: 10
                val localSub = SubActions { error("unused-$idBefore") }
                ctx.sub = localSub
                ctx.pacedWaitMs += wait.toLong()
                ctx.idleScrolls += 1
                withContext(Dispatchers.IO) { delay(30) }
                val idAfter = ctx.callId
                val subSame = ctx.sub === localSub
                val waitAfter = ctx.pacedWaitMs
                val scrollsAfter = ctx.idleScrolls
                val stepAfter = ctx.currentStep ?: -1
                return ToolExecution(
                    content = buildJsonObject {
                        put("ok", true)
                        put("idBefore", idBefore ?: "")
                        put("idAfter", idAfter ?: "")
                        put("subSame", subSame)
                        put("waitAfter", waitAfter)
                        put("scrollsAfter", scrollsAfter)
                        put("stepAfter", stepAfter)
                    },
                )
            }
        }

        val config = ToolConfig(
            visionEnabled = false,
            skills = null,
            recordFrames = false,
            vision = null,
            inlineScreen = false,
            adaptiveVision = false,
            memory = null,
            profile = null,
            utterances = null,
            conversationId = null,
            imageOps = null,
        )
        val dispatcher = ToolDispatcher(
            capabilities = listOf(phoneCap),
            tools = listOf(parallelProbe),
            sections = emptyList(),
            config = config,
            surfaceLayer = { ctx ->
                SpySurfaceLayer(Interfaces.attach(ctx, listOf(phoneCap.surface!!), false)).also { spySurface = it }
            },
        )
        val executor = ActionExecutor(dispatcher, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE), autonomous = true)

        val jobs = (1..12).map { idx ->
            async(Dispatchers.Default) {
                executor.run(
                    Part.ToolCall("call-$idx", "parallel_probe", """{"wait":${idx * 10}}"""),
                    Origin.Model(idx),
                )
            }
        }
        val outcomes = jobs.awaitAll()

        assertEquals("ParallelSafe + offScreen must never touch SurfaceLayer", 0, spySurface.surfaceTouches.get())
        assertEquals(12, dispatcher.ledgerMark())
        assertEquals(12, dispatcher.evidence(fresh = false).actions.size)
        outcomes.forEachIndexed { i, outcome ->
            val idx = i + 1
            val ran = outcome as? ActionOutcome.Ran ?: error("expected Ran, got $outcome")
            val c = ran.exec.content
            assertEquals("call-$idx", c["idBefore"]!!.jsonPrimitive.content)
            assertEquals("call-$idx", c["idAfter"]!!.jsonPrimitive.content)
            assertTrue("ctx.sub must stay isolated per call", c["subSame"]!!.jsonPrimitive.content.toBoolean())
            assertEquals((idx * 10).toLong(), ran.exec.waitMs)
            assertEquals(1, c["scrollsAfter"]!!.jsonPrimitive.content.toInt())
            assertEquals(idx, c["stepAfter"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun parallelSafeReadWithOffScreenFalseAlsoSkipsSurfaceLayerAndAppAccess() = runBlocking(Dispatchers.Default) {
        val blockedScreen = object : ScreenPort by FakeScreen() {
            override suspend fun checkAppAccess(): Unit = throw AppAccessDenied("app_blocked", "bank")
        }
        val phoneCap = PhoneCapability(blockedScreen, deepLinks = false)
        lateinit var spySurface: SpySurfaceLayer
        val onScreenFalseParallel = object : AgentTool {
            override val def = ToolDef(name = "parallel_read_no_offscreen", description = "parallel read")
            override val verb = Verb.HistoryRead
            override val offScreen = false
            override val risk = Risk.Read
            override val concurrency = Concurrency.ParallelSafe
            override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
                withContext(Dispatchers.IO) { delay(15) }
                return ToolExecution(content = buildJsonObject { put("ok", true); put("id", ctx.callId ?: "") })
            }
        }
        val config = ToolConfig(
            visionEnabled = false,
            skills = null,
            recordFrames = false,
            vision = null,
            inlineScreen = false,
            adaptiveVision = false,
            memory = null,
            profile = null,
            utterances = null,
            conversationId = null,
            imageOps = null,
        )
        val dispatcher = ToolDispatcher(
            capabilities = listOf(phoneCap),
            tools = listOf(onScreenFalseParallel),
            sections = emptyList(),
            config = config,
            surfaceLayer = { ctx ->
                SpySurfaceLayer(Interfaces.attach(ctx, listOf(phoneCap.surface!!), false)).also { spySurface = it }
            },
        )
        val executor = ActionExecutor(dispatcher, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE), autonomous = true)

        val outcomes = (1..8).map { idx ->
            async(Dispatchers.Default) {
                executor.run(Part.ToolCall("r-$idx", "parallel_read_no_offscreen", "{}"), Origin.Model(idx))
            }
        }.awaitAll()

        assertEquals(0, spySurface.surfaceTouches.get())
        outcomes.forEachIndexed { i, outcome ->
            val ran = outcome as? ActionOutcome.Ran ?: error("expected Ran, got $outcome")
            assertEquals("r-${i + 1}", ran.exec.content["id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun actionRecorderShotIdsAndOutwardActsAreThreadSafe() = runBlocking(Dispatchers.Default) {
        val recorder = ActionRecorder(RunSink.NONE, saveScreen = { _, _ -> })
        val shotIds = (1..24).map {
            async(Dispatchers.Default) {
                recorder.saveFrame(byteArrayOf(1, 2, 3))!!
            }
        }.awaitAll()
        assertEquals((1..24).toSet(), shotIds.toSet())

        (1..24).map { idx ->
            async(Dispatchers.Default) {
                recorder.approval(
                    Origin.Model(idx),
                    "phone_tap",
                    Irreversible(IrrevAction.send, "send"),
                    ApprovalRisk(ActionRisk.External),
                    "allowed",
                )
            }
        }.awaitAll()
        assertEquals(24, recorder.outwardActs)
    }
}
