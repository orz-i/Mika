package com.wangzan101.mika.atlas

import com.agent.use.ui.AppAtlas
import com.agent.use.ui.AtlasStore
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.agent.use.ui.ScreenSnapshot
import com.wangzan101.mika.settings.AesGcmKeyCipher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class AtlasAssemblyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dummyScreen = object : ScreenPort {
        override suspend fun snapshot() = ScreenSnapshot("p0", null, emptyList(), 1080, 2400)
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok()
        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok()
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
        override suspend fun back() = ActionResult.Ok()
        override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok()
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps() = emptyList<Pair<String, String>>()
    }

    private fun testAtlasStore(): AtlasStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return FileAtlasStore(tmp.newFolder("atlas"), AesGcmKeyCipher { key }).apply {
            put(AppAtlas(packageName = "com.example.app", appLabel = "示例应用"))
        }
    }

    @Test fun toolDispatcherExposesAtlasToolsOnlyWhenAtlasProvidedAndWritable() {
        val store = testAtlasStore()
        val normalSpec = RunSpec(RunId("r1"), "c1", "打开设置")

        val withAtlas = toolDispatcher(screen = dummyScreen, runSpec = normalSpec, atlas = store)
            .definitions().map { it.name }.toSet()
        assertTrue("phone_run_shortcut" in withAtlas)
        assertTrue("phone_navigate_to" in withAtlas)

        val withoutAtlas = toolDispatcher(screen = dummyScreen, runSpec = normalSpec, atlas = null)
            .definitions().map { it.name }.toSet()
        assertFalse("phone_run_shortcut" in withoutAtlas)
        assertFalse("phone_navigate_to" in withoutAtlas)

        val readOnlySpec = normalSpec.copy(readOnly = true)
        val readOnly = toolDispatcher(screen = dummyScreen, runSpec = readOnlySpec, atlas = store)
            .definitions().map { it.name }.toSet()
        assertFalse("phone_run_shortcut" in readOnly)
        assertFalse("phone_navigate_to" in readOnly)
    }
}
