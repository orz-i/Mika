package com.wangzan101.mika.core.cap.sandbox

import com.wangzan101.mika.core.guard.Severity
import com.wangzan101.mika.core.policy.GuardMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxGuardTest {
    private fun cmdArgs(cmd: String = "python3 test.py"): JsonObject =
        buildJsonObject { put("command", cmd) }

    private fun fail(exitCode: Int, output: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("exitCode", exitCode)
        put("timedOut", false)
        put("killedBySystem", false)
        put("durationMs", 10L)
        put("output", output)
    }

    @Test fun warnsFromThreeAndOnlyStopsAtHighSafetyFuse() {
        val guard = SandboxGuard()
        val args = cmdArgs()
        val sameFail = fail(2, "No such file")
        repeat(2) {
            assertTrue(guard.result(RunCommand.name, args, sameFail).isEmpty())
        }
        // 第 3~14 次仅注入带诊断建议的 Warn，不在第 5 次提前熔断
        for (i in 3 until SANDBOX_LOOP_STOP) {
            val v = guard.result(RunCommand.name, args, sameFail).single()
            assertEquals("sandbox_loop", v.code)
            assertEquals(Severity.Warn, v.severity)
            assertEquals(i, v.evidence["repeatCount"]!!.jsonPrimitive.int)
            assertNotNull(v.nudge)
            assertNull(guard.stop)
        }
        val stopped = guard.result(RunCommand.name, args, sameFail).single()
        assertEquals(Severity.Stop, stopped.severity)
        assertEquals(SANDBOX_LOOP_STOP, stopped.evidence["repeatCount"]!!.jsonPrimitive.int)
        assertNotNull(guard.stop)
    }

    @Test fun outputOrExitCodeChangeResetsRepeatCount() {
        val guard = SandboxGuard()
        val args = cmdArgs("pytest")
        assertTrue(guard.result(RunCommand.name, args, fail(1, "SyntaxError line 1")).isEmpty())
        assertTrue(guard.result(RunCommand.name, args, fail(1, "SyntaxError line 1")).isEmpty())
        assertEquals(Severity.Warn, guard.result(RunCommand.name, args, fail(1, "SyntaxError line 1")).single().severity)

        // 输出发生变化 -> 重置计数
        assertTrue(guard.result(RunCommand.name, args, fail(1, "AssertionError line 9")).isEmpty())
        assertTrue(guard.result(RunCommand.name, args, fail(1, "AssertionError line 9")).isEmpty())
        // 退出码发生变化 -> 再次重置计数
        assertTrue(guard.result(RunCommand.name, args, fail(2, "AssertionError line 9")).isEmpty())
        assertNull(guard.stop)
    }

    @Test fun successfulWriteFileResetsCommandLoopCount() {
        val guard = SandboxGuard()
        val args = cmdArgs("make build")
        val sameFail = fail(2, "build failed")
        repeat(2) { guard.result(RunCommand.name, args, sameFail) }
        assertEquals(Severity.Warn, guard.result(RunCommand.name, args, sameFail).single().severity)

        guard.result(WriteFile.name, buildJsonObject { put("path", "/workspace/a.c"); put("content", "int main(){}") },
            buildJsonObject { put("ok", true) })

        assertTrue(guard.result(RunCommand.name, args, sameFail).isEmpty())
        assertTrue(guard.result(RunCommand.name, args, sameFail).isEmpty())
        assertNull(guard.stop)
    }

    @Test fun warnOnlyModeNeverStops() {
        val guard = SandboxGuard(mode = GuardMode.WARN_ONLY)
        val args = cmdArgs()
        val sameFail = fail(1, "err")
        repeat(SANDBOX_LOOP_STOP + 5) { idx ->
            val v = guard.result(RunCommand.name, args, sameFail)
            if (idx + 1 >= SANDBOX_LOOP_WARN) {
                assertEquals(Severity.Warn, v.single().severity)
            }
        }
        assertNull(guard.stop)
    }
}
