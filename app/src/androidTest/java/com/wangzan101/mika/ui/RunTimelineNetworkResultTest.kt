package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.record.RunStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class RunTimelineNetworkResultTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun expandAndCollapseCapturedRequestsAndResponseBody() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "test-timeline-net").toFile()
        val runs = RunStore(dir, { null }, keep = 1, clock = System::currentTimeMillis) { it }
        val runId = runs.open("抓包测试").use { handle ->
            val now = System.currentTimeMillis()
            handle.event(RunEvent.ModelReply(now, 1, "看看请求", "phone_inspect_network", """{"limit":5}""", 50, 10, 0, 20))
            handle.event(
                RunEvent.ToolResult(
                    now + 10, 1, "phone_inspect_network",
                    buildJsonObject {
                        put("ok", true)
                        put("count", 1)
                        put("capturing", true)
                        put("activeMode", "VPN")
                        put("requests", JsonArray(listOf(
                            buildJsonObject {
                                put("requestId", "r-101")
                                put("url", "https://api.example.com/v1/feed")
                                put("method", "GET")
                                put("statusCode", 200)
                                put("durationMs", 35)
                                put("timestamp", now)
                            },
                        )))
                    }.toString(),
                    changed = null, screenshotId = null,
                ),
            )
            handle.event(RunEvent.ModelReply(now + 20, 2, "读取响应", "phone_get_network_response", """{"requestId":"r-101"}""", 50, 10, 0, 20))
            handle.event(
                RunEvent.ToolResult(
                    now + 30, 2, "phone_get_network_response",
                    buildJsonObject {
                        put("ok", true)
                        put("requestId", "r-101")
                        put("url", "https://api.example.com/v1/feed")
                        put("statusCode", 200)
                        put("contentType", "application/json")
                        put("body", Untrusted.wrap("""{"status":"healthy"}"""))
                        put("truncated", false)
                    }.toString(),
                    changed = null, screenshotId = null,
                ),
            )
            handle.event(RunEvent.TaskEnded(now + 40, "succeeded", "好了"))
            handle.runId
        }

        try {
            val vm = RunTimelineViewModel(runs, runId)
            compose.activityRule.scenario.onActivity { it.setContent { RunTimelineScreen(vm, onBack = {}) } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("看当时的网络请求").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithText("看当时的网络请求").performScrollTo().performClick()
            compose.onNodeWithText("收起网络请求").assertIsDisplayed()
            compose.onNodeWithText("https://api.example.com/v1/feed").assertIsDisplayed()

            compose.onNodeWithText("看当时的网络响应体").performScrollTo().performClick()
            compose.onNodeWithText("收起网络响应体").assertIsDisplayed()
            compose.onNodeWithText("healthy", substring = true).assertIsDisplayed()
        } finally {
            dir.deleteRecursively()
        }
    }
}
