package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.use.a11y.model.A11yNode

/**
 * 读屏读到的一个节点,两种实现:
 *
 * - [LiveNode] 引用型:握着真正的 [AccessibilityNodeInfo],状态实时读,能发 ACTION_*、能 refresh。
 * - [FrozenNode] 快照型:只有读屏那一刻的字段副本,没有节点引用,动作只能按坐标做。
 *
 * 上层一律经这个接口取状态;真需要无障碍引用的地方取 [raw](快照型为 null)。
 * 于是「无障碍服务不可用」只是让这些地方退回坐标路径,不会整条链断掉。
 */
internal interface NodeHandle {
    /** 节点引用;快照型没有。 */
    val raw: AccessibilityNodeInfo?

    /** 有节点引用,能发无障碍动作。 */
    val isLive: Boolean

    /** 屏幕坐标:引用型是当前值,快照型是读屏那一刻的值。 */
    fun bounds(): Rect

    val visible: Boolean
    val enabled: Boolean
    val clickable: Boolean
    val scrollable: Boolean
    val password: Boolean
    val focused: Boolean
    val editable: Boolean
    val className: String?
    val packageName: String?
    val viewId: String?

    /**
     * 判「还是不是同一个节点」用的键:引用型是节点本身
     * ([AccessibilityNodeInfo.equals] 比连接、窗口、节点号,跨次读屏也认得出同一个控件);
     * 快照型由位置、类型、资源 ID、文字组成,各次读屏之间可比。
     */
    val identity: Any

    /** 引用型重新取一次状态;快照型没有可刷新的,恒为 false。 */
    fun refresh(): Boolean

    /** 发一个无障碍动作;快照型发不了,恒为 false。 */
    fun performAction(action: Int): Boolean
}

/** 引用型:一个真节点。每次取属性都问系统,和直接读节点等价。 */
internal class LiveNode(val node: AccessibilityNodeInfo) : NodeHandle {
    override val raw: AccessibilityNodeInfo get() = node
    override val isLive: Boolean get() = true

    override fun bounds(): Rect = Rect().also { node.getBoundsInScreen(it) }

    override val visible: Boolean get() = node.isVisibleToUser
    override val enabled: Boolean get() = node.isEnabled
    override val clickable: Boolean get() = node.isClickable
    override val scrollable: Boolean get() = node.isScrollable
    override val password: Boolean get() = node.isPassword
    override val focused: Boolean get() = node.isFocused
    override val editable: Boolean get() = ScreenReader.canSetText(node)
    override val className: String? get() = node.className?.toString()
    override val packageName: String? get() = node.packageName?.toString()
    override val viewId: String? get() = node.viewIdResourceName
    override val identity: Any get() = node

    override fun refresh(): Boolean = runCatching { node.refresh() }.getOrDefault(false)

    override fun performAction(action: Int): Boolean =
        runCatching { node.performAction(action) }.getOrDefault(false)

    override fun toString(): String = "LiveNode(${viewId ?: className})"
}

/** 快照型的一个节点身份:同一屏里按 [path] 分开,跨屏按其余字段认。 */
private data class FrozenKey(
    val path: String,
    val className: String?,
    val viewId: String?,
    val text: String?,
    val bounds: String,
)

/**
 * 快照型:一份 [A11yNode] 副本。[path] 是节点在这棵树里的路径(读屏顺序号),
 * 只用来在同一屏里区分节点,不参与语义。
 */
internal class FrozenNode(private val snap: A11yNode, private val path: String) : NodeHandle {
    override val raw: AccessibilityNodeInfo? get() = null
    override val isLive: Boolean get() = false

    private val rect = Rect(snap.bounds)
    private val key = FrozenKey(path, snap.className, snap.viewId, snap.text ?: snap.contentDescription, rect.flattenToString())

    override fun bounds(): Rect = Rect(rect)

    // 快照来自 isVisibleToUser 的节点(解析时已经滤掉不可见的)
    override val visible: Boolean get() = true
    override val enabled: Boolean get() = snap.isEnabled
    override val clickable: Boolean get() = snap.isClickable
    override val scrollable: Boolean get() = snap.isScrollable
    override val password: Boolean get() = snap.isPassword
    override val focused: Boolean get() = snap.isFocused
    override val editable: Boolean get() = snap.canSetText || snap.isEditable
    override val className: String? get() = snap.className
    override val packageName: String? get() = snap.packageName
    override val viewId: String? get() = snap.viewId
    override val identity: Any get() = key

    override fun refresh(): Boolean = false

    override fun performAction(action: Int): Boolean = false

    override fun toString(): String = "FrozenNode(${snap.viewId ?: snap.className} $rect)"
}

/**
 * 这个节点自己或最近的可滚动祖先。引用型沿父链往上找;快照型没有父链,只认自己可滚。
 * 返回 null 表示「没有能发滚动动作的控件」,调用方按手势滚。
 */
internal fun NodeHandle.scrollTarget(): NodeHandle? {
    var cur: AccessibilityNodeInfo? = raw ?: return takeIf { scrollable }
    var depth = 0
    while (cur != null && depth++ < SCROLL_ANCESTOR_DEPTH) {
        if (cur.isScrollable) return LiveNode(cur)
        cur = runCatching { cur?.parent }.getOrNull()
    }
    return null
}

private const val SCROLL_ANCESTOR_DEPTH = 8
