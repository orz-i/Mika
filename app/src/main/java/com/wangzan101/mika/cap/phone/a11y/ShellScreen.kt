package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import com.agent.use.a11y.DisplayWindowInspector
import com.agent.use.a11y.ScreenBounds
import com.agent.use.a11y.ScreenNodeDescriptor
import com.agent.use.a11y.model.A11yNode
import org.json.JSONObject

/**
 * shell 通道读到的一屏。
 *
 * 与 [ScreenRead] 的区别：这里只有**快照**，没有 `AccessibilityNodeInfo` 引用，
 * 所以后续动作只能按坐标做（点击走内核注入、输入走剪贴板粘贴），
 * 不能调用 `performAction` 或 `refresh`。
 */
internal data class ShellScreen(
    val packageName: String?,
    val displayId: Int,
    val roots: List<A11yNode>,
    val count: Int,
) {
    /** 摊平成一维，便于按文字/资源 id 检索。 */
    val flat: List<A11yNode> get() = roots.flatMap { it.findNodes { _ -> true } }
}

/**
 * 把 [com.agent.use.common.uiauto.UiAutomationReader.dump] 的扁平 JSON 还原成节点树。
 *
 * JSON 里每个节点用 `parent` 指向父节点下标（-1 为根），因此不需要嵌套结构，
 * 也就不会因为层级过深在 Binder 上放大体积。
 */
internal object ShellScreenParser {

    fun parse(json: String?): ShellScreen? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!root.optBoolean("ok", false)) return null
        val array = root.optJSONArray("nodes") ?: return null

        data class Raw(val node: A11yNode, val parent: Int, val visible: Boolean)

        val raw = ArrayList<Raw>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            raw += Raw(
                node = A11yNode(
                    text = o.optString("text").ifEmpty { null },
                    contentDescription = o.optString("desc").ifEmpty { null },
                    viewId = o.optString("id").ifEmpty { null },
                    className = o.optString("cls").ifEmpty { null },
                    packageName = root.optString("pkg").ifEmpty { null },
                    bounds = Rect(
                        o.optInt("l"), o.optInt("t"), o.optInt("r"), o.optInt("b"),
                    ),
                    isClickable = o.optInt("click") == 1,
                    isEditable = o.optInt("edit") == 1,
                    isScrollable = o.optInt("scroll") == 1,
                    isFocused = o.optInt("focus") == 1,
                    isSelected = o.optInt("sel") == 1,
                    isEnabled = o.optInt("en", 1) == 1,
                    isPassword = o.optInt("pwd") == 1,
                    hintText = o.optString("hint").ifEmpty { null },
                    isCheckable = o.optInt("chk") != 0,
                    isChecked = o.optInt("chk") == 2,
                ),
                parent = o.optInt("parent", -1),
                visible = o.optInt("vis", 1) == 1,
            )
        }
        if (raw.isEmpty()) return null

        val kids = Array(raw.size) { mutableListOf<Int>() }
        raw.forEachIndexed { index, item ->
            val p = item.parent
            if (p in kids.indices && p != index) kids[p] += index
        }

        // 不可见的节点自己不进树,但它下面可见的子节点要接上来(和 DisplayWindowInspector 的过滤语义一致)
        fun visibleKids(index: Int): List<Int> = kids[index].flatMap { j ->
            if (raw[j].visible) listOf(j) else visibleKids(j)
        }

        // 自底向上会重复构建；这里按索引缓存，只构建一次
        val built = arrayOfNulls<A11yNode>(raw.size)
        fun build(index: Int): A11yNode {
            built[index]?.let { return it }
            val node = raw[index].node.copy(children = visibleKids(index).map(::build))
            built[index] = node
            return node
        }
        val roots = raw.indices
            .filter { raw[it].parent !in raw.indices }
            .flatMap { if (raw[it].visible) listOf(build(it)) else visibleKids(it).map(::build) }
        return ShellScreen(
            packageName = root.optString("pkg").ifEmpty { null },
            displayId = root.optInt("display", -1),
            roots = roots,
            count = raw.size,
        )
    }
}
