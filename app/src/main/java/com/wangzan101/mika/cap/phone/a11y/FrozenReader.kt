package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import com.agent.use.a11y.DisplayWindowInspector
import com.agent.use.a11y.ScreenBounds
import com.agent.use.a11y.ScreenNodeDescriptor
import com.agent.use.a11y.model.A11yNode
import com.wangzan101.mika.core.uistate.ElementLocator
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiStates
import com.wangzan101.mika.core.uistate.UiTreeNode
import com.wangzan101.mika.core.uistate.UiWindowTree
import com.wangzan101.mika.core.uistate.normalize

/**
 * 把 shell 通道读到的一屏(只有 [A11yNode] 快照)整理成和 [ScreenReader.read] 同形的 [ScreenRead]。
 *
 * 元素、文字、界面指纹、结构签名都按同一条规则生成,区别只在节点是 [FrozenNode]:
 * 拿不到 `AccessibilityNodeInfo`,所以后续动作只能按坐标做(见 [NodeHandle])。
 * 这样无障碍服务不可用时,读屏对上层仍然是一屏可用的元素表,而不是一块空屏。
 */
internal object FrozenReader {
    private const val MIN_SIZE = 5
    private const val NORMALIZED_SCALE = 1000
    private const val WINDOW_ID = 0

    fun read(screen: ShellScreen, width: Int, height: Int, collectSignature: Boolean = false): ScreenRead {
        val entries = mutableListOf<NodeEntry>()
        val texts = mutableListOf<String>()
        val seen = HashSet<String>()
        var hash = DisplayWindowInspector.FNV_OFFSET
        var password = false
        var lastLabel: String? = null
        val signatureNodes = if (collectSignature) mutableListOf<ScreenNodeDescriptor>() else null

        fun visit(n: A11yNode, path: String, inList: Boolean, sigOut: MutableList<ScreenNodeDescriptor>?): UiTreeNode {
            val cls = n.className
            val rawText = n.text
            val hint = n.hintText?.trim()?.ifEmpty { null }
            val secret = n.isPassword
            val editable = n.canSetText || n.isEditable
            if (secret) password = true
            // 密码框不把字交给模型,名字退回提示语(和引用型读屏一致)
            val own = if (secret) hint else n.label.ifEmpty { null }
            val text = rawText?.takeUnless { secret || it.isBlank() }
            val desc = n.contentDescription?.takeUnless { secret || it.isBlank() }

            val rect = Rect(n.bounds)
            val clipped = Rect(rect)
            if (!clipped.intersect(0, 0, width, height)) clipped.setEmpty()
            val bounds = listOf(
                normalize(clipped.left, width), normalize(clipped.top, height),
                normalize(clipped.right, width), normalize(clipped.bottom, height),
            )
            val stateSig = "c${n.isChecked}s${n.isSelected}e${n.isEnabled}"
            hash = DisplayWindowInspector.mixHash(hash, cls, text, desc, rect.flattenToString(), stateSig)
            own?.let(texts::add)

            val showingHint = n.isShowingHintText || (rawText.isNullOrBlank() && hint != null)
            val nodeIsList = isList(n)
            val childrenInList = inList || nodeIsList
            val childSigs = if (sigOut != null) mutableListOf<ScreenNodeDescriptor>() else null
            val childPath = if (path.isEmpty()) "" else "$path."
            val children = n.children.mapIndexed { i, c -> visit(c, "$childPath$i", childrenInList, childSigs) }

            var ref: String? = null
            val usable = clipped.width() >= MIN_SIZE && clipped.height() >= MIN_SIZE
            val interactive = n.isClickable || editable || n.isScrollable
            if (usable && (own != null || interactive)) {
                val role = n.role
                val label = own.orEmpty()
                if (seen.add("$role|$label|${rect.flattenToString()}")) {
                    val id = "@e${entries.size + 1}"
                    ref = id
                    val element = UiElement(
                        ref = id, role = role, name = label,
                        centerX = rect.centerX(), centerY = rect.centerY(),
                        bounds = bounds,
                        resourceId = if (label.isEmpty() || editable) n.readableResourceId else null,
                        clickable = n.isClickable, editable = editable, scrollable = n.isScrollable,
                        hint = own != null && showingHint,
                        focused = n.isFocused,
                        secret = secret,
                        placeholder = if (editable) hint else null,
                        searchField = editable && cls?.contains("Search", ignoreCase = true) == true,
                        states = uiStates(n),
                    )
                    val locator = ElementLocator(role, label, n.viewId, rect.centerX(), rect.centerY(), lastLabel, element.hint, inList)
                    // 只留这个节点自己的字段:带上 children 会把整棵子树钉在编号登记表里
                    entries += NodeEntry(element, locator, FrozenNode(n.copy(children = emptyList()), path))
                    if (label.isNotEmpty()) lastLabel = label
                }
            }

            if (sigOut != null && childSigs != null) {
                sigOut += ScreenNodeDescriptor(
                    role = n.role,
                    text = text,
                    resourceId = n.viewId,
                    bounds = ScreenBounds.fromList(bounds),
                    clickable = n.isClickable,
                    editable = editable,
                    scrollable = n.isScrollable,
                    children = childSigs,
                    contentDescription = desc,
                    hintText = hint,
                    isHint = editable && showingHint,
                    checkable = n.isCheckable,
                    checked = n.isChecked,
                    selected = n.isSelected,
                    enabled = n.isEnabled,
                    password = secret,
                    visible = true,
                    isListContainer = nodeIsList,
                )
            }

            return UiTreeNode(
                role = n.role, bounds = bounds, ref = ref,
                text = text,
                description = desc,
                hintText = hint,
                resourceId = n.viewId,
                clickable = n.isClickable, editable = editable, scrollable = n.isScrollable,
                checkable = n.isCheckable, checked = n.isChecked, selected = n.isSelected,
                enabled = n.isEnabled, focused = n.isFocused, secret = secret,
                wheel = cls?.endsWith("NumberPicker") == true,
                children = children,
            )
        }

        val trees = screen.roots.mapIndexed { i, root ->
            val sigOut = signatureNodes
            UiWindowTree(
                id = WINDOW_ID, type = "application", layer = 0, title = null,
                packageName = screen.packageName, active = true, focused = true,
                nodes = listOf(visit(root, "w$i", false, sigOut)),
            )
        }
        return ScreenRead(
            entries = entries,
            hash = hash,
            texts = texts,
            passwordField = password,
            hasAppWindow = screen.roots.isNotEmpty(),
            topPackage = screen.packageName,
            windows = trees,
            anyAppWindow = screen.roots.isNotEmpty(),
            signatureNodes = signatureNodes.orEmpty(),
        )
    }

    /** 列表类容器:子项会复用,同一位置可能换成别的条目。 */
    private fun isList(n: A11yNode): Boolean {
        val cls = n.className.orEmpty()
        return cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView") ||
            (n.isScrollable && (cls.contains("List", ignoreCase = true) || cls.contains("Recycler", ignoreCase = true)))
    }

    private fun uiStates(n: A11yNode): Set<String> = buildSet {
        if (n.isChecked) add(UiStates.CHECKED)
        if (n.isSelected) add(UiStates.SELECTED)
        if (!n.isEnabled) add(UiStates.DISABLED)
    }
}
