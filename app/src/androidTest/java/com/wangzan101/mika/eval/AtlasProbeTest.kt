package com.wangzan101.mika.eval

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.atlas.FileAtlasStore
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机查看某个 App 已学到的页面地图,只读不写图谱。
 *
 * `adb shell am instrument -w -e class com.wangzan101.mika.eval.AtlasProbeTest -e atlasPkg <包名> ...`
 * 报告写到 App 外部文件目录 `eval/atlas-probe-<包名>.txt`,同时在 logcat 打一份。
 * 用来核对:页面数、跳转边(强/弱/失效)、入口边、快捷流程。
 */
@RunWith(AndroidJUnit4::class)
class AtlasProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun dumpAtlas() {
        val pkg = args.getString("atlasPkg")?.trim().orEmpty()
        assumeTrue("atlasPkg is required", pkg.isNotEmpty())
        val atlas = FileAtlasStore.shared(context).get(pkg)
        assumeTrue("no atlas for $pkg", atlas != null)
        requireNotNull(atlas)

        val text = buildString {
            appendLine("package=${atlas.packageName} label=${atlas.appLabel}")
            appendLine("screens=${atlas.screens.size} edges=${atlas.edges.size} shortcuts=${atlas.shortcuts.size}")
            val entry = atlas.entryEdges()
            appendLine("entryEdges=${entry.size}")
            appendLine()
            appendLine("[screens]")
            for (s in atlas.screens.sortedByDescending { it.lastSeenMs }) {
                val title = s.displayTitle().ifBlank { s.fallbackTitle() }
                appendLine(
                    "- ${s.sigId} | $title | source=${s.titleSource} hits=${s.hits} controls=${s.controls.size}",
                )
            }
            appendLine()
            appendLine("[edges]")
            for (e in atlas.edges.sortedByDescending { it.lastAttemptMs }) {
                val target = e.target?.let { it.name.ifBlank { it.resourceId.orEmpty() } }.orEmpty()
                appendLine(
                    "- ${e.fromSigId} -> ${e.toSigId} | ${e.tool} | $target | " +
                        "ok=${e.ok} weak=${e.weakOk} miss=${e.miss} | entry=${e.isEntry()}",
                )
            }
            appendLine()
            appendLine("[shortcuts]")
            for (s in atlas.shortcuts) {
                appendLine(
                    "- ${s.id} | ${s.name} | steps=${s.steps.size} " +
                        "successes=${s.successes} misses=${s.consecutiveMisses} disabled=${s.disabled}",
                )
            }
        }

        val out = File(context.getExternalFilesDir(null), "eval/atlas-probe-$pkg.txt")
        out.parentFile?.mkdirs()
        out.writeText(text)
        println(text)
        println("written: ${out.absolutePath}")
    }
}
