package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.text.TextEntities
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolConfig
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.visual.Shots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `phone_entities` 的登记与开关：模型没接上时不下发，接上才下发。 */
class PhoneEntitiesTest {

    @Test
    fun isRegisteredWithThePhoneTools() {
        assertTrue(PhoneGroup.tools.any { it.name == "phone_entities" })
    }

    @Test
    fun isOfferedOnlyWhenAnExtractorIsWired() {
        assertTrue(PhoneEntities.available(context(TextEntities { emptyList() })))
        assertFalse(PhoneEntities.available(context(null)))
    }

    @Test
    fun onlyReads() {
        assertEquals(Risk.Read, PhoneEntities.risk)
        assertEquals("phone", PhoneEntities.capability)
    }

    private fun context(entities: TextEntities?): ToolContext = ToolContext(
        config = ToolConfig(
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
            textEntities = entities,
        ),
        evidence = EvidenceLog(),
        tools = ToolIndex(PhoneGroup.tools),
        scripts = null,
        shots = Shots(),
    )
}
