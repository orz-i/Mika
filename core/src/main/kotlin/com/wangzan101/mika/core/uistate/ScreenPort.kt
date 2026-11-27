package com.wangzan101.mika.core.uistate

import com.agent.use.ui.ScreenSurface
import com.agent.use.ui.Verification
import com.wangzan101.mika.core.visual.FrameGrid

const val DOUBLE_TAP_UNSUPPORTED = "double_tap_unsupported"

/** 两次点击之间的间隔:够短才会被认成双击(安卓的双击时限是 300 毫秒)。 */
const val DOUBLE_TAP_GAP_MS = 120L

/** 滑动手势的默认时长。 */
const val DEFAULT_SWIPE_MS = 400L

/** 内置助手以 Android shell 身份执行命令的结果。 */
data class SystemCommandResult(val exitCode: Int, val output: String)

sealed interface ActionResult {
    /**
     * 动作已发出。其余字段是端口事后核实到的事实,null/false 表示没有核实或不适用:
     * [changed] 动作前后界面是否变化;[appSwitched] 前台已不是目标 App 时的当前包名;
     * [relocated] 编号已过期、按原元素特征在新界面里找回后执行,[relocatedTo] 是找回的元素现在的名字;
     * [switchedByTarget] 新前台是目标 App 在自己的任务里打开的(如分享到另一个 App),不是外来打断;[verification] 输入回读结果;
     * [hint] 端口对这次结果的补充说明(例如为什么核实不了),原样转给模型;
     * [secret] 输入的目标是密码框(运行记录据此不记原文);
     * [settled] 网页有 DevTools 时动作后等到了网络空闲且页面安静为 true,到上限还没静下来为 false,其余为 null;
     * [coveredBy] 打开的 App 已在最上面,但另一个包的窗口盖在它上面(权限弹窗、账号选择器)时那个包;
     * [chooser] 打开 App 后系统先问「打开哪一个」(同一 App 装了两份等),选好之前 App 还没到前台;
     * [opened] 这个动作让一个 App 开到了用户的主屏上(见 [OpenedElsewhere]);
     * [inProfile] 刚到前台的 App 装了两份时开的是哪一份(见 [OpenedElsewhere.inProfile]):系统问过打开哪一个之后,
     * 以及目标 App 列出过另一份、或要的就是另一份时每次都报;[profileUser] 是此刻屏上那一份所属的用户编号,
     * [requestedUser] 是打开时要的那份用户(要的是本来那份为 null)。
     */
    data class Ok(
        val changed: Boolean? = null,
        val appSwitched: String? = null,
        val relocated: Boolean = false,
        val relocatedTo: String? = null,
        val switchedByTarget: Boolean = false,
        val verification: Verification? = null,
        val hint: String? = null,
        val secret: Boolean = false,
        val settled: Boolean? = null,
        val coveredBy: String? = null,
        val chooser: LaunchChoice? = null,
        val opened: OpenedElsewhere? = null,
        val inProfile: String? = null,
        val profileUser: Int? = null,
        val requestedUser: Int? = null,
    ) : ActionResult

    /** 引用已过期时 reason 为 "ref_expired"。[hint] 是端口的补充说明(例如盖住目标的是什么),原样附在错误说明后。 */
    data class Failed(val reason: String, val hint: String? = null) : ActionResult
}

/** 打开 App 时这个 App 正被另一个任务用着(同一个 App 同时只能在一块屏上)。 */
const val APP_IN_USE_BY_OTHER_TASK = "app_in_use_by_other_task"

/** 打开 App 时用户正把它摆在自己的屏幕上用:端口不抢,只回这个原因。 */
const val APP_IN_USE_BY_USER = "app_in_use_by_user"

/**
 * 要的是冷启动,端口却只做了热启动时 hint 的前缀:这个 App 在别的屏上还有任务(用户自己开过、退到了后台),
 * 结束它会把用户那一边的状态一起抹掉,所以端口没有结束它、只是重新把它提到前台。
 * 调用方若必须要一次真正的进程重起(例如抓包要蹲 natives 注册),看到这个前缀就得自己补一步。
 */
const val HINT_COLD_DEGRADED = "coldIgnored"

/** [ScreenPort.awaitAppFree] 的结果。 */
enum class AppWait {
    /** 放开了,可以打开了。 */
    Free,
    /** 等到时限仍被占着。 */
    TimedOut,
    /** 占着它的任务正在等这个任务手里的 App:等下去谁也等不到,没有等。 */
    WouldDeadlock,
    /** 这个端口不和别的任务争 App,没什么可等。 */
    Unsupported,
}

/**
 * Agent 观察和操作手机屏幕的唯一入口,由 Android 模块实现。
 *
 * 读屏、等待与诊断的通用部分继承自 [ScreenSurface]（在 aau-ui 里，任何宿主都能复用）；
 * 这里只剩动作、App 访问策略、任务结束去留、抓包端口这些本产品自己的语义。
 */
interface ScreenPort : ScreenSurface {
    val appAccess: com.wangzan101.mika.core.access.AppAccessPolicy get() = com.wangzan101.mika.core.access.AppAccessPolicy.ALLOW

    /** 仅核对窗口归属,不读取屏幕内容。空屏可用;有窗口但归属未知时拒绝。 */
    suspend fun checkAppAccess() {}

    /** 固定的能力声明；问它不连接助手、不建屏，也不加载模型。 */
    val supportsLocalIcons: Boolean get() = false

    /**
     * 应用插件包的根目录（内置包 `capture_plugins_builtin/`、用户包 `capture_plugins/`）。
     *
     * 插件包不只有抓包规则，还可以带 `flows` 应用脚本（见 [com.agent.use.flow.AppFlow]）。
     * 目录由宿主给：core 是纯 JVM，不认 `filesDir`。
     */
    val flowPackRoots: List<java.io.File> get() = emptyList()

    suspend fun findLocalIcon(icon: LocalIcon, region: LocalIconRegion): LocalIconResult = LocalIconResult.Miss.Unavailable

    /** 仅用于已有的点击执行入口。端口必须在落指前核对帧；不支持时绝不能退回普通坐标点击。 */
    suspend fun tapLocalIcon(x: Int, y: Int, frame: LocalIconFrame): ActionResult = ActionResult.Failed("located_stale")

    suspend fun tapRef(ref: String, snapshotId: String): ActionResult
    suspend fun tapXY(x: Int, y: Int): ActionResult
    /** 双击(两次点击间隔在系统认双击的时限内);不支持的端口报 double_tap_unsupported。 */
    suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Failed(DOUBLE_TAP_UNSUPPORTED)
    suspend fun doubleTapXY(x: Int, y: Int): ActionResult = ActionResult.Failed(DOUBLE_TAP_UNSUPPORTED)
    suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult
    suspend fun clearText(ref: String?, snapshotId: String?): ActionResult

    /**
     * 往这一点([x],[y] 为像素)上的输入框里打字:先点它,点出了拿着焦点的输入框就照常打,树里看不到输入框时经剪贴板粘贴、
     * 比打字前后屏上的字核对;粘贴后前后一点没变为 point_not_typed(那一点多半不是输入框,或没拿到焦点)。
     * 默认先点再按焦点打字,不另核对那一点。
     */
    suspend fun typeAt(x: Int, y: Int, text: String): ActionResult =
        tapXY(x, y).let { if (it is ActionResult.Ok) typeText(null, null, text) else it }

    /** 清空这一点上的输入框,做法同 [typeAt]。 */
    suspend fun clearAt(x: Int, y: Int): ActionResult =
        tapXY(x, y).let { if (it is ActionResult.Ok) clearText(null, null) else it }
    suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = DEFAULT_SWIPE_MS): ActionResult

    /**
     * 停留时顺手轻轻滚一下([fraction] 个屏幕或视口高度,正为往下),不点任何东西。网页走和往下滚一样的原生输入、不移动指针;
     * 手机在占了大半屏的那块可滚动内容上慢慢划一小段。这会儿不便滚(有弹框、输入法开着、输入框拿着焦点、看不到可滚的内容)时不滚,
     * 返回 false;滚了为 true。默认不滚。
     */
    suspend fun idleScroll(fraction: Double): Boolean = false
    /** 多点手势；每条路径是按时间先后排列的像素点，所有手指同时移动。 */
    suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = ActionResult.Failed("unsupported")
    suspend fun back(): ActionResult
    suspend fun pressKey(keyCode: Int): ActionResult
    suspend fun openApp(packageName: String, cold: Boolean = false): ActionResult

    /**
     * 打开装在另一份用户 [userId] 里的那份 [packageName](分身、工作资料,见 [appCopies]);其余同 [openApp]。
     * 那份用户不能用、或那里没装这个 App 时失败 copy_not_found。不支持的端口返回 unsupported。
     */
    suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean = false): ActionResult = ActionResult.Failed("unsupported")

    /**
     * [packageName](另一份用户 [userId] 里的那份;null 是本来那份)正被另一个任务用着时([APP_IN_USE_BY_OTHER_TASK]),
     * 最多等 [timeoutMs] 到它放开。只是等,不打开:等到了调用方再打开一次。占着它的任务自己正在等这个任务手里的
     * App(互相等,谁也等不到)时不等,当场返回 [AppWait.WouldDeadlock]。不和别的任务争 App 的端口返回 [AppWait.Unsupported]。
     */
    suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait = AppWait.Unsupported

    /**
     * 在 Agent 的屏上打开一个链接或安卓设置页([action] 为 null 时按 VIEW 打开 [uri]);[packageName] 限定由哪个 App 打开。
     * 失败原因:link_unresolved(没有 App 能打开)、invalid_link、app_in_use_by_user。不支持的端口返回 unsupported。
     */
    suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult = ActionResult.Failed("unsupported")

    /**
     * 把一段文字分享给 [packageName](ACTION_SEND text/plain),在 Agent 的屏上打开它的分享页;只打开,不发出。
     * 这个 App 没有收文字分享的页面时失败 cannot_share;其余和 [openApp] 一样(建屏、用户正在用、系统问打开哪一个)。不支持的端口返回 unsupported。
     */
    suspend fun shareText(packageName: String, text: String): ActionResult = ActionResult.Failed("unsupported")

    /**
     * 经安卓标准接口做一个系统动作(见 [SystemAction]):先查有没有 App 接,没有报 not_supported;有就在 Agent 的屏上交给它
     * (第一次用时建屏,用户正在主屏用那个 App 时不打开)。不支持的端口返回 unsupported。
     */
    suspend fun systemAction(action: SystemAction): SystemActionResult = SystemActionResult(ActionResult.Failed("unsupported"))

    /**
     * 经安卓日历提供方一次写入一批日程，不打开日历界面。调用方已完整校验全部事件；
     * 权限不足、没有可写日历或写入结果不确定时返回对应失败原因，不自动换路径重写。
     */
    suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult =
        SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))

    /**
     * 把编号 [ref] 那个滚轮(或滚轮里的一项)转到显示 [value]:在端口里一次做完(能输入就输入,能无障碍滚就一格格滚,
     * 都不行就按一格多高拖),每做几下读回一次。不支持的端口报 unsupported。
     */
    suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome =
        WheelOutcome(false, null, null, 0, "none", error = "unsupported")
    /** 直接经任务持有的 shell 助手执行命令。 */
    suspend fun systemCommand(command: String): SystemCommandResult? = null
    suspend fun listApps(): List<Pair<String, String>> // 包名, 显示名

    /** 也装在这台手机另一份用户里(分身、工作资料等)的 App:包名 → 那几份用户的名字。查不到或不支持时为空。 */
    suspend fun otherProfiles(): Map<String, List<String>> = emptyMap()

    /** 能打开的另一份 App(装在分身、工作资料一类的另一份用户里,规则见 [AppCopies]);查不到或不支持时为空。 */
    suspend fun appCopies(): List<AppCopy> = emptyList()

    /**
     * 任务结束时这份 App 怎么办(见 [AfterTaskMarks]):只记下,此刻什么也不动。不支持的端口(没有任务结束时的去留)为 null。
     */
    val afterTask: AfterTaskMarks? get() = null

    /** 此刻画面缩成的亮度格子(判动作效果用,见 [FrameGrid.sample]);没有画面、端口不支持时为 null。要便宜:不编码图片。 */
    suspend fun frameGrid(): FrameGrid? = null

    /** 取出刚刚在中间页/确认弹窗等待完成后落定、尚未附进工具结果的越屏/回捞报告；没有为 null。 */
    suspend fun drainSettledSpill(): OpenedElsewhere? = null

    /** 手机端网络抓包端口；设备不支持或未启用抓包时为 null。 */
    val network: com.wangzan101.mika.core.net.PhoneNetworkPort? get() = null
}
