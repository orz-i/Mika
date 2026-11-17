package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.agent.use.a11y.DisplayWindowInspector
import com.agent.use.a11y.ScreenBounds
import com.agent.use.a11y.ScreenNodeDescriptor
import com.agent.use.a11y.ScreenSignature
import com.agent.use.a11y.ScreenSignatureExtractor
import com.wangzan101.mika.core.cap.phone.WheelMath
import com.wangzan101.mika.core.uistate.ElementLocator
import com.wangzan101.mika.core.uistate.ScreenSig
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiStates
import com.wangzan101.mika.core.uistate.UiTreeNode
import com.wangzan101.mika.core.uistate.UiWindowTree
import com.wangzan101.mika.core.uistate.ambiguousFieldReadback
import com.wangzan101.mika.core.uistate.normalize

/**
 * 读一次屏的结果。[hash] 覆盖所有可见节点(含标成「对无障碍不重要」的)的文字、类型、位置、勾选、
 * 输入焦点和光标/选区,用来判断界面变没变:点输入框只换了焦点也算变了。
 */
internal class ScreenRead(
    val entries: List<NodeEntry>,
    val hash: Long,
    val texts: List<String>,
    val passwordField: Boolean,
    val hasAppWindow: Boolean,
    val topPackage: String?,
    val windows: List<UiWindowTree> = emptyList(),
    /** 有应用窗口(不管根节点读不读得到)。 */
    val anyAppWindow: Boolean = hasAppWindow,
    /** 用于提取页面结构签名的轻量节点树（仅在按需采集时填充，归一化坐标 0–999）。 */
    val signatureNodes: List<ScreenNodeDescriptor> = emptyList(),
)

/** 从某块屏的窗口里读出元素列表、界面指纹和诊断用的文字。通用窗口过滤、标签提取与界面指纹委托给 [DisplayWindowInspector]。 */
internal object ScreenReader {
    private const val MIN_SIZE = 5
    private const val MAX_DEPTH = 80
    private const val NORMALIZED_SCALE = 1000
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** 从上到下排好的、属于界面内容的窗口。 */
    fun contentWindows(windows: List<AccessibilityWindowInfo>): List<AccessibilityWindowInfo> =
        DisplayWindowInspector.contentWindows(windows)

    // 最上面的应用窗口可能是读不到内容的浮层(弹出菜单、输入框的选字浮窗等),跳过它们
    fun topPackage(windows: List<AccessibilityWindowInfo>): String? =
        DisplayWindowInspector.topPackage(windows)

    @Suppress("DEPRECATION")
    fun read(
        windows: List<AccessibilityWindowInfo>,
        width: Int,
        height: Int,
        collectSignature: Boolean = false,
    ): ScreenRead {
        val content = contentWindows(windows)
        val entries = mutableListOf<NodeEntry>()
        val texts = mutableListOf<String>()
        val seen = HashSet<String>()
        var hash = DisplayWindowInspector.FNV_OFFSET
        var password = false
        var lastLabel: String? = null
        val rect = Rect()

        fun visit(
            n: AccessibilityNodeInfo,
            depth: Int,
            inList: Boolean,
            sigOut: MutableList<ScreenNodeDescriptor>?,
        ): List<UiTreeNode> {
            if (depth > MAX_DEPTH) return emptyList()
            val visible = n.isVisibleToUser
            val editable = visible && canSetText(n)
            var ref: String? = null
            var clippedBounds = listOf(0, 0, 0, 0)
            var nodeRole = "View"
            var nodeRid: String? = null
            var nodeSecret = false
            var nodeHintFlag = false
            var nodeText: String? = null
            var nodeDesc: String? = null
            var nodeHint: String? = null
            if (visible) {
                n.getBoundsInScreen(rect)
                val rawClass = n.className
                val rawText = n.text
                val rawDesc = n.contentDescription
                hash = mix(hash, rawClass, rawText, rawDesc, rect.flattenToString(), states(n))
                nodeSecret = n.isPassword
                if (nodeSecret) password = true
                nodeRole = roleOf(n)
                nodeRid = n.viewIdResourceName
                nodeText = rawText?.toString()?.takeUnless { nodeSecret || it.isBlank() }
                nodeDesc = rawDesc?.toString()?.takeUnless { nodeSecret || it.isBlank() }
                nodeHint = n.hintText?.toString()?.takeUnless { it.isBlank() }
                val own = if (nodeSecret) nodeHint?.trim()?.ifEmpty { null } else ownLabel(n)
                own?.let(texts::add)
                clip(rect, width, height)
                clippedBounds = listOf(normalize(rect.left, width), normalize(rect.top, height),
                    normalize(rect.right, width), normalize(rect.bottom, height))
                val usable = rect.width() >= MIN_SIZE && rect.height() >= MIN_SIZE
                val interactive = n.isClickable || editable || n.isScrollable
                nodeHintFlag = (usable && (own != null || interactive) || (sigOut != null && editable)) && showingHint(n)
                if (usable && (own != null || interactive)) {
                    val label = labelOf(n, own)
                    if (seen.add("$nodeRole|$label|${rect.flattenToString()}")) {
                        val id = "@e${entries.size + 1}"
                        ref = id
                        val element = UiElement(
                            ref = id, role = nodeRole, name = label,
                            centerX = rect.centerX(), centerY = rect.centerY(),
                            bounds = clippedBounds,
                            // 输入框总带上资源 ID:回车前要靠它认出搜索框(名字常常只是推荐词)
                            resourceId = if (label.isEmpty() || editable) readableResourceId(nodeRid) else null,
                            clickable = n.isClickable, editable = editable, scrollable = n.isScrollable,
                            hint = own != null && nodeHintFlag,
                            focused = n.isFocused,
                            secret = nodeSecret,
                            placeholder = if (editable) nodeHint?.trim()?.ifEmpty { null } else null,
                            // 安卓的搜索框控件(SearchView 里的输入框)按类名认
                            searchField = editable && rawClass?.toString()?.contains("Search", ignoreCase = true) == true,
                            states = uiStates(n),
                        )
                        val locator = ElementLocator(nodeRole, label, nodeRid, rect.centerX(), rect.centerY(), lastLabel, element.hint, inList)
                        entries += NodeEntry(element, locator, n)
                        if (label.isNotEmpty()) lastLabel = label
                    }
                }
            }
            val nodeIsList = isList(n)
            val childrenInList = inList || nodeIsList
            val childSigs = if (sigOut != null) mutableListOf<ScreenNodeDescriptor>() else null
            val children = buildList {
                for (i in 0 until n.childCount) n.getChild(i)?.let { addAll(visit(it, depth + 1, childrenInList, childSigs)) }
            }
            if (!visible) {
                if (sigOut != null && childSigs != null) sigOut += childSigs
                return children
            }
            if (sigOut != null && childSigs != null) {
                sigOut += ScreenNodeDescriptor(
                    role = nodeRole,
                    text = nodeText,
                    resourceId = nodeRid,
                    bounds = ScreenBounds.fromList(clippedBounds),
                    clickable = n.isClickable,
                    editable = editable,
                    scrollable = n.isScrollable,
                    children = childSigs,
                    contentDescription = nodeDesc,
                    hintText = nodeHint,
                    isHint = editable && nodeHintFlag,
                    checkable = n.isCheckable,
                    checked = n.isChecked,
                    selected = n.isSelected,
                    enabled = n.isEnabled,
                    password = nodeSecret,
                    visible = true,
                    isListContainer = nodeIsList,
                )
            }
            return listOf(UiTreeNode(
                role = nodeRole, bounds = clippedBounds, ref = ref,
                text = nodeText,
                description = nodeDesc,
                hintText = nodeHint,
                resourceId = nodeRid,
                clickable = n.isClickable, editable = editable, scrollable = n.isScrollable,
                checkable = n.isCheckable, checked = n.isChecked, selected = n.isSelected,
                enabled = n.isEnabled, focused = n.isFocused, secret = nodeSecret,
                wheel = isWheel(n),
                children = children,
            ))
        }

        val signatureNodes = if (collectSignature) mutableListOf<ScreenNodeDescriptor>() else null
        val trees = content.map { w ->
            val root = w.root
            val rootPkg = root?.packageName?.toString()?.takeIf { it.isNotBlank() }
            // 跳过未聚焦的 SystemUI（状态栏/导航栏），保留应用与聚焦弹窗窗口用于结构签名
            val skipForSig = (w.type == AccessibilityWindowInfo.TYPE_SYSTEM || rootPkg == SYSTEM_UI_PACKAGE) &&
                !w.isFocused && !w.isActive
            val winSigOut = if (signatureNodes != null && !skipForSig && root != null) signatureNodes else null
            UiWindowTree(
                id = w.id,
                type = when (w.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                    AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                    else -> "other"
                },
                layer = w.layer, title = w.title?.toString(), packageName = root?.packageName?.toString(),
                active = w.isActive, focused = w.isFocused,
                nodes = root?.let { visit(it, 0, false, winSigOut) }.orEmpty(),
            )
        }
        return ScreenRead(
            entries = entries,
            hash = hash,
            texts = texts,
            passwordField = password,
            hasAppWindow = content.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null },
            anyAppWindow = content.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION },
            topPackage = topPackage(windows),
            windows = trees,
            signatureNodes = signatureNodes.orEmpty(),
        )
    }

    /** 将 shell 返回的裸类名或简写组件名安全规范化为 `packageName/fullClassName`。 */
    fun normalizeTopActivity(packageName: String?, rawActivity: String?): String? =
        ScreenSig.normalizeActivityName(packageName, rawActivity)

    /** 从一次读屏结果提取页面结构签名；受保护页面、无应用窗口或空白页面返回 null。 */
    fun extractSignature(
        read: ScreenRead,
        activityName: String? = null,
        protected: Boolean = false,
    ): ScreenSig? {
        if (protected || !read.hasAppWindow || read.topPackage.isNullOrBlank()) return null
        return extractSignature(
            nodes = read.signatureNodes,
            packageName = read.topPackage,
            activityName = activityName,
            protected = false,
        )
    }

    /** 从轻量节点列表提取页面结构签名（坐标默认为 0–999 归一化尺度）；受保护或空白时返回 null。 */
    fun extractSignature(
        nodes: List<ScreenNodeDescriptor>,
        packageName: String?,
        activityName: String? = null,
        protected: Boolean = false,
    ): ScreenSig? {
        if (protected || nodes.isEmpty()) return null
        val sig = ScreenSignatureExtractor.extract(
            nodes = nodes,
            packageName = packageName,
            activityName = activityName,
            screenWidth = NORMALIZED_SCALE,
            screenHeight = NORMALIZED_SCALE,
        )
        return toScreenSig(sig)
    }

    /** 将 `aau-a11y` 的 [ScreenSignature] 转为核心层纯 JVM [ScreenSig]；空白签名返回 null。 */
    fun toScreenSig(sig: ScreenSignature): ScreenSig? {
        if (sig.isBlank) return null
        return ScreenSig(
            sigId = sig.sigId,
            activity = sig.activity,
            fingerprint = sig.fingerprint,
            anchors = sig.anchors,
            resourceIds = sig.resourceIds,
            packageName = sig.packageName,
            shortActivity = sig.shortActivity,
            isBlank = sig.isBlank,
        )
    }

    /** 单个节点当前的特征(不含上下文);节点不可见或太小时为 null。 */
    fun locatorOf(n: AccessibilityNodeInfo, width: Int, height: Int): ElementLocator? {
        if (!n.isVisibleToUser) return null
        val rect = Rect().also { n.getBoundsInScreen(it) }
        clip(rect, width, height)
        if (rect.width() < MIN_SIZE || rect.height() < MIN_SIZE) return null
        val own = ownLabel(n)
        return ElementLocator(
            roleOf(n), labelOf(n, own), n.viewIdResourceName, rect.centerX(), rect.centerY(), null,
            hint = own != null && showingHint(n), inList = inList(n),
        )
    }

    /**
     * 看上去是滚轮选择器:原版的数字滚轮控件,或能滚、里面是几项在正中竖着等距排开的短值(规则见 [WheelMath.looksLikeWheel],
     * 宁可漏认)。有一项读不出字就不算。
     */
    fun isWheel(n: AccessibilityNodeInfo): Boolean {
        if (n.className?.toString()?.endsWith("NumberPicker") == true) return true
        if (!n.isScrollable || n.childCount !in 3..12) return false
        val box = Rect().also(n::getBoundsInScreen)
        val r = Rect()
        val items = mutableListOf<WheelMath.Item>()
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (!c.isVisibleToUser) continue
            val label = ownLabel(c) ?: c.takeIf { it.childCount == 1 }?.getChild(0)?.let(::ownLabel) ?: return false
            c.getBoundsInScreen(r)
            items += WheelMath.Item(label, r.centerX(), r.centerY())
        }
        return WheelMath.looksLikeWheel(items.sortedBy { it.centerY }, box.left, box.right)
    }

    /** 列表类容器:子项会复用,同一位置可能换成别的条目。 */
    private fun isList(n: AccessibilityNodeInfo): Boolean = DisplayWindowInspector.isList(n)

    private fun inList(n: AccessibilityNodeInfo): Boolean = DisplayWindowInspector.inList(n, MAX_DEPTH)

    private fun clip(rect: Rect, width: Int, height: Int) {
        if (!rect.intersect(0, 0, width, height)) rect.setEmpty()
    }

    private fun roleOf(n: AccessibilityNodeInfo) = DisplayWindowInspector.roleOf(n)

    fun labelOf(n: AccessibilityNodeInfo, own: String? = ownLabel(n)) = DisplayWindowInspector.labelOf(n, own)

    /** 只算界面指纹,不建元素表(动作前后比对用)。 */
    fun hash(windows: List<AccessibilityWindowInfo>): Long = DisplayWindowInspector.hash(windows, MAX_DEPTH)

    /** 判动作效果用的控件状态(和 [hash] 看的同一批)。 */
    @Suppress("DEPRECATION")
    private fun uiStates(n: AccessibilityNodeInfo): Set<String> = buildSet {
        if (n.isChecked) add(UiStates.CHECKED)
        if (n.isSelected) add(UiStates.SELECTED)
        if (!n.isEnabled) add(UiStates.DISABLED)
    }

    private fun states(n: AccessibilityNodeInfo) = DisplayWindowInspector.nodeStateSignature(n)

    /** 节点自己的名字:文字(提示语也算,输入框靠它说明用途),没有就用内容描述。 */
    fun ownLabel(n: AccessibilityNodeInfo): String? = DisplayWindowInspector.ownLabel(n)

    /**
     * 节点上显示的是提示语:系统标了 showingHintText,或没有实际文字、名字来自提示语,
     * 或报出的文字就是提示语本身(有的输入框不设那个标记)。
     */
    private fun showingHint(n: AccessibilityNodeInfo): Boolean = DisplayWindowInspector.showingHint(n)

    /** 输入框正在显示的提示语;没在显示提示语时为 null。 */
    fun placeholderOf(n: AccessibilityNodeInfo): String? = DisplayWindowInspector.placeholderOf(n)

    /** Some accessibility providers expose the action without setting the editable flag. */
    fun canSetText(n: AccessibilityNodeInfo): Boolean = DisplayWindowInspector.canSetText(n)

    /**
     * 输入框里用户可见的实际文字;显示的是提示语时为空串,密码框读不出时为 null。
     * [placeholder] 是输入前看到的提示语:有的输入框打字后不再标「显示提示语」,却仍把它当文字报出来,这时也算空。
     * 但要输入的 [typing] 本身就等于提示语时,没标「显示提示语」的同样文字就当作已输入。
     * 读回呈「提示语 + 空白 + 目标文字」时无法分清标签和用户正文,返回 null,不删前缀也不触发重写。
     */
    fun fieldText(n: AccessibilityNodeInfo, placeholder: String? = null, typing: String? = null): String? {
        if (n.isPassword) return null
        if (n.isShowingHintText) return ""
        val text = n.text?.toString() ?: return ""
        val shown = text.trim()
        if (typing != null && shown == typing.trim()) return text
        if (showingHint(n) || (placeholder != null && shown == placeholder)) return ""
        if (ambiguousFieldReadback(shown, placeholder, typing)) return null
        return text
    }

    /** `com.x:id/cart_icon` → `cart_icon`;混淆过的短名认不出用途,不给。 */
    private fun readableResourceId(id: String?): String? = DisplayWindowInspector.readableResourceId(id)

    private fun mix(h0: Long, vararg parts: Any?): Long = DisplayWindowInspector.mixHash(h0, *parts)
}
