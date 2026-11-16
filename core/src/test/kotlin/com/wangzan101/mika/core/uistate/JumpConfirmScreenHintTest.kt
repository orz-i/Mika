package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.custody.InterstitialActor
import com.wangzan101.mika.core.custody.InterstitialChoice
import com.wangzan101.mika.core.custody.JumpConfirmDegradeReason
import com.wangzan101.mika.core.custody.JumpConfirmOutcome
import com.wangzan101.mika.core.custody.JumpConfirmReport
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpConfirmScreenHintTest {

    @Test
    fun `OpenedElsewhere toJson omits jumpConfirm when null and serializes when present`() {
        val plain = OpenedElsewhere(packageName = "com.taobao.taobao", app = "淘宝", pulledBack = true)
        assertNull(plain.toJson()["jumpConfirm"])
        assertFalse(plain.onUserScreen)

        val auto = OpenedElsewhere(
            packageName = "com.taobao.taobao",
            app = "淘宝",
            pulledBack = true,
            jumpConfirm = JumpConfirmReport(
                initiator = "tv.danmaku.bili",
                target = "com.taobao.taobao",
                by = InterstitialActor.AGENT,
                choice = InterstitialChoice.ALLOW,
                outcome = JumpConfirmOutcome.AUTO_ALLOWED,
            ),
        )
        assertFalse(auto.onUserScreen)
        val jcJson = auto.toJson()["jumpConfirm"]!!.jsonObject
        assertEquals("tv.danmaku.bili", jcJson["initiator"]!!.jsonPrimitive.content)
        assertEquals("com.taobao.taobao", jcJson["target"]!!.jsonPrimitive.content)
        assertEquals("agent", jcJson["by"]!!.jsonPrimitive.content)
        assertEquals("allow", jcJson["choice"]!!.jsonPrimitive.content)
        assertEquals("auto_allowed", jcJson["outcome"]!!.jsonPrimitive.content)

        val waiting = OpenedElsewhere(
            packageName = "com.miui.securitycenter",
            app = "Taobao",
            pulledBack = false,
            jumpConfirm = JumpConfirmReport(
                initiator = "Bilibili",
                target = "Taobao",
                by = null,
                choice = InterstitialChoice.UNKNOWN,
                outcome = JumpConfirmOutcome.WAITING_USER,
                degradeReason = JumpConfirmDegradeReason.HOSTING_DISABLED,
            ),
        )
        assertTrue(waiting.onUserScreen)
        val waitJson = waiting.toJson()
        assertTrue(waitJson["onUserScreen"]!!.jsonPrimitive.boolean)
        assertEquals(
            "hosting_disabled",
            waitJson["jumpConfirm"]!!.jsonObject["degradeReason"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `openedHint selects jump_confirm feedback templates across wait auto denied unavailable`() {
        val waitHint = openedHint(
            OpenedElsewhere(
                packageName = "com.miui.securitycenter",
                app = "Taobao",
                pulledBack = false,
                jumpConfirm = JumpConfirmReport(
                    initiator = "Bilibili",
                    target = "Taobao",
                    outcome = JumpConfirmOutcome.WAITING_USER,
                ),
            ),
        )
        assertTrue(waitHint.contains("Bilibili"))
        assertTrue(waitHint.contains("Taobao"))
        assertTrue(waitHint.contains("hand_to_user"))

        val autoHint = openedHint(
            OpenedElsewhere(
                packageName = "com.taobao.taobao",
                app = "Taobao",
                pulledBack = true,
                jumpConfirm = JumpConfirmReport(
                    initiator = "Bilibili",
                    target = "Taobao",
                    by = InterstitialActor.AGENT,
                    choice = InterstitialChoice.ALLOW,
                    outcome = JumpConfirmOutcome.AUTO_ALLOWED,
                ),
            ),
        )
        assertTrue(autoHint.contains("automatically confirmed"))
        assertTrue(autoHint.contains("Taobao"))

        val denied = OpenedElsewhere(
            packageName = "com.miui.securitycenter",
            app = "Taobao",
            pulledBack = false,
            jumpConfirm = JumpConfirmReport(
                initiator = "Bilibili",
                target = "Taobao",
                by = InterstitialActor.USER,
                choice = InterstitialChoice.DENY,
                outcome = JumpConfirmOutcome.DENIED,
            ),
        )
        assertFalse(denied.onUserScreen)
        val deniedHint = openedHint(denied)
        assertTrue(deniedHint.contains("denied"))

        val unavailableHint = openedHint(
            OpenedElsewhere(
                packageName = "com.miui.securitycenter",
                app = "Taobao",
                pulledBack = false,
                jumpConfirm = JumpConfirmReport(
                    initiator = "Bilibili",
                    target = "Taobao",
                    outcome = JumpConfirmOutcome.UNAVAILABLE,
                    degradeReason = JumpConfirmDegradeReason.DEVICE_CIRCUIT_BROKEN,
                ),
            ),
        )
        assertTrue(unavailableHint.contains("unavailable"))
        assertTrue(unavailableHint.contains("hand_to_user"))
    }
}
