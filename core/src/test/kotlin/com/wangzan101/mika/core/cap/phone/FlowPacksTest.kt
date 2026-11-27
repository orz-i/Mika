package com.wangzan101.mika.core.cap.phone

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 插件包目录的读取：内置一套、用户一套，同一个 flow id 由用户那份说了算。
 *
 * 这条规则是「现场改 JSON 就能试」的前提：改了内置包同名脚本却还跑旧的那份，
 * 会让人以为改错了地方。
 */
class FlowPacksTest {

    /** 目录名必须和真机上一致：内置包靠 `capture_plugins_builtin` 这个后缀认出来。 */
    private fun root(kind: String): File {
        val base = Files.createTempDirectory("flowpacks-").toFile()
        base.deleteOnExit()
        return File(base, kind).also { it.mkdirs() }
    }

    private fun pack(root: File, dir: String, flowId: String, label: String) {
        val at = File(root, "$dir/plugin.json")
        at.parentFile.mkdirs()
        at.writeText(
            """{"id":"$dir","apiVersion":1,"packages":["a.b"],"flows":[
               {"id":"$flowId","steps":[{"op":"log","text":"$label"}]}]}"""
        )
    }

    @Test
    fun externalPackOverridesBuiltinFlowWithTheSameId() {
        val builtin = root("capture_plugins_builtin")
        val external = root("capture_plugins")
        pack(builtin, "qidian", "reward-video", "builtin")
        pack(external, "qidian", "reward-video", "external")

        val snapshot = FlowPacks.load(listOf(builtin, external))

        assertEquals("同名脚本不该出现两条", 1, snapshot.flows.size)
        assertEquals("external.qidian", snapshot.flows.single().packId)
    }

    @Test
    fun twoDifferentFlowsBothStay() {
        val builtin = root("capture_plugins_builtin")
        val external = root("capture_plugins")
        pack(builtin, "qidian", "reward-video", "builtin")
        pack(external, "other", "daily", "external")

        val snapshot = FlowPacks.load(listOf(builtin, external))

        assertEquals(
            listOf("builtin.qidian/reward-video", "external.other/daily").sorted(),
            snapshot.flows.map { "${it.packId}/${it.id}" }.sorted(),
        )
    }
}
