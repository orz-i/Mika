package com.wangzan101.mika.cap.phone

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.testTasks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 插件包里的应用脚本（`flows`）在真机上跑通：装包 → 按前台 App 挑脚本 → 读屏 → 落指。
 *
 * 用脚本模型驱动一次真实任务，所以走的是产品里同一条路：`phone_run_flow` → `SubActions` →
 * 无障碍读屏/点按。这里不验起点那一套文案（素材一换就变），只验「脚本是数据、执行靠宿主」
 * 这条机制；起点那份脚本能走多远由 `flow` 回执里的 `stopped` 说话。
 */
@RunWith(AndroidJUnit4::class)
class FlowPackDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = ScriptedModel()

    @Before
    fun setUp() {
        DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
        File(context.filesDir, "capture_plugins/settings-smoke").deleteRecursively()
    }

    private fun lastToolResult(call: ScriptedModel.Call): String =
        call.messages.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    /** 长跑时从命令行给参数：`-Pandroid.testInstrumentationRunnerArguments.flowRounds=18`。 */
    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    /**
     * 回执写进外部私有目录（`Android/data/.../files`），`adb pull` 直接取。
     *
     * 一轮十八步、十几轮下来 trace 上百条，打进 logcat 会被截断；落成文件才能事后逐轮对。
     */
    private fun dump(name: String, text: String): File? = runCatching {
        File(context.getExternalFilesDir(null) ?: context.filesDir, name).apply { writeText(text) }
    }.getOrNull()

    private fun startTask(goal: String) =
        TaskService.start(context, goal, "flow-pack-${UUID.randomUUID()}")

    /**
     * 用户目录里的包：写进 `capture_plugins/`，任务里就能用，不用重启 App。
     *
     * 脚本只做「等设置页出现 → 点搜索框 → 停下来」，点的东西改不了系统状态，跑完还能退回去。
     */
    @Test
    fun externalPackFlowRunsAgainstTheForegroundApp() {
        val pack = File(context.filesDir, "capture_plugins/settings-smoke/plugin.json")
        pack.parentFile.mkdirs()
        pack.writeText(
            """
            {
              "id": "settings-smoke",
              "apiVersion": 1,
              "packages": ["${DeviceSupport.SETTINGS}"],
              "flows": [{
                "id": "settings-smoke",
                "steps": [
                  {"op": "log", "text": "等设置页"},
                  {"op": "wait", "query": {"text": "设置"}, "timeoutMs": 10000},
                  {"op": "tap", "query": {"text": "搜索设置"}, "optional": true},
                  {"op": "wait", "query": {"text": "搜索"}, "timeoutMs": 3000, "optional": true},
                  {"op": "stop", "reason": "ok"}
                ]
              }]
            }
            """.trimIndent()
        )

        startTask("打开设置跑一段脚本")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"${DeviceSupport.SETTINGS}"}""")
        model.awaitCall()
        model.feed("phone_run_flow", """{"flow":"settings-smoke"}""")
        val result = json(lastToolResult(model.awaitCall()))

        assertTrue(result.toString(), result["ok"]?.jsonPrimitive?.content == "true")
        assertEquals("settings-smoke", result["flow"]?.jsonPrimitive?.content)
        assertEquals("external.settings-smoke", result["pack"]?.jsonPrimitive?.content)
        assertEquals("ok", result["stopped"]?.jsonPrimitive?.content)
        assertTrue(result.toString(), result["steps"]?.jsonPrimitive?.content!!.toInt() >= 4)
        assertTrue(result.toString(), result["log"].toString().contains("等设置页"))

        model.answer("跑完了")
        val end = pollUntil(60_000) {
            DeviceTaskState.state.value.takeIf { it is TaskState.Succeeded || it is TaskState.Failed }
        }
        assertTrue("$end", end is TaskState.Succeeded)
    }

    /**
     * 内置的起点包：第一次取插件包目录时从 assets 装到 `capture_plugins_builtin/`，
     * 打开起点后按包名就能挑到 `reward-video`。这里只走开头几步，看到 `not_found`/`ad_not_shown`
     * 都算机制通了——文案对不对由真机上那次运行说话。
     */
    @Test
    fun builtinQidianPackIsInstalledAndSelected() {
        // 别的机器上没有起点：跳过，别把「没装」算成失败
        assumeTrue(
            "startup reader not installed",
            runCatching { context.packageManager.getPackageInfo("com.qidian.QDReader", 0) }.isSuccess,
        )
        startTask("打开起点跑激励视频脚本")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"com.qidian.QDReader"}""")
        // 起点冷启动比设置慢得多：开屏、书架、广告都要走完
        model.awaitCall(120_000)
        val rounds = arg("flowRounds")?.toIntOrNull() ?: 1
        model.feed("phone_run_flow", """{"flow":"reward-video","params":{"rounds":$rounds}}""")
        // 一轮 = 等广告 + 正片 15 秒 + 关闭 + 领奖，实测 40 秒上下；余量按轮数给
        val receipt = lastToolResult(model.awaitCall(120_000 + rounds * 70_000L))
        dump("flow-receipt-r$rounds.json", receipt)
        val result = json(receipt)

        assertEquals(result.toString(), "reward-video", result["flow"]?.jsonPrimitive?.content)
        assertEquals(result.toString(), "builtin.qidian", result["pack"]?.jsonPrimitive?.content)
        val stopped = result["stopped"]?.jsonPrimitive?.content.orEmpty()
        // 起点会回到上次那一页（福利中心）：一轮应当走完，停在轮次上限。
        // 停在首页时会是 not_found:去完成——那也说明脚本读到了真屏幕，只是这一屏没有任务。
        // 今天的次数用完时，入口变成「已领取」，脚本停在 all_claimed。
        assertTrue(
            result.toString(),
            stopped == "round_limit" || stopped.startsWith("not_found") ||
                stopped == "ad_not_shown" || stopped == "all_claimed",
        )
        if (stopped == "round_limit") {
            assertTrue(result.toString(), result["steps"]?.jsonPrimitive?.content!!.toInt() >= 18)
        }

        model.answer("跑完了")
        val end = pollUntil(90_000) {
            DeviceTaskState.state.value.takeIf { it is TaskState.Succeeded || it is TaskState.Failed }
        }
        assertTrue("$end", end is TaskState.Succeeded)
    }
}
