package com.wangzan101.mika.cap.phone.a11y

import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.a11y.MikaAccessibilityService

/** 真机测试共用:shell 连接、无障碍服务、虚拟屏上的 AndroidScreen。需要内置助手在运行(已配对、无线调试开着)且 Mika 无障碍服务已开启。 */
object DeviceSupport {
    const val SETTINGS = "com.android.settings"
    /** The test APK inherits the app's optional .dev suffix; never hard-code the package here. */
    val TEST_PACKAGE: String
        get() = InstrumentationRegistry.getInstrumentation().context.packageName
    private var connection: ShellBinding? = null
    private val phone get() = PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     * 与 App 共用进程内的那条 shell 连接并一直占着:各自绑定再各自解绑会让先解绑的一方结束 shell 服务,
     * 另一方手里的 binder 随之失效。
     */
    @Synchronized
    fun shell(): IMikaShell {
        val c = connection ?: phone.sharedShell.acquire().also {
            connection = it
        }
        // 必须先拿到活着的连接再动无障碍列表:重绑本身要跑 settings 命令
        val live = awaitShell(c)
        rebindAccessibility(live)
        return live
    }

    /** 等连接拿到活着的助手;等不到就带着当前状态失败,提示先配对。助手被杀后看门狗重起要一会儿,断开回调到达前也可能还拿着已死的 binder。 */
    fun awaitShell(c: ShellBinding, timeoutMs: Long = 30_000): IMikaShell = runBlocking {
        withTimeoutOrNull(timeoutMs) { c.shell.filterNotNull().first { it.asBinder().pingBinder() } }
    } ?: error(
        "Mika's phone helper is not running (status=${phone.shellControl.status.value}): " +
            "pair it once in Mika (设置手机操作) with Wireless debugging on, keep Wireless debugging on, then rerun",
    )

    /** 服务掉线(前面的测试结束了本进程的绑定等)时先促使系统重绑,再等它连上。 */
    fun service(): ScreenAccessibility {
        // shell() 拿到活连接后会顺手把无障碍服务重绑回来,这里只要触发一次
        if (ScreenAccessibility.instance.value == null) shell()
        return runBlocking {
            withTimeoutOrNull(10_000) { ScreenAccessibility.instance.filterNotNull().first() }
        } ?: error("Mika accessibility service is not enabled")
    }

    class Agent(val display: AgentDisplay, val screen: AndroidScreen) {
        fun release() {
            screen.close()
            display.release()
        }
    }

    fun newAgent(): Agent {
        val shell = shell()
        val d = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        return Agent(d, AndroidScreen(service(), shell, ready(d)))
    }

    /** 已经建好的屏:测试自己先建屏(还可能先往上放 App),读屏不必等 phone_launch_app。 */
    fun ready(d: AgentDisplay): LazyAgentDisplay = LazyAgentDisplay(d.width, d.height) { d }.also { runBlocking { it.get() } }

    /**
     * 启动测试时系统会强停本 App,无障碍服务随之被记为已崩溃、不再自动重绑。
     * 已开启时把它从开启列表里摘下再放回,促使系统重新绑定;没开启就不动。
     */
    private fun rebindAccessibility(shell: IMikaShell) {
        if (ScreenAccessibility.instance.value != null) return
        val key = "enabled_accessibility_services"
        val mika = "${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/${MikaAccessibilityService::class.java.name}"
        val enabled = raw(shell, "settings get secure $key").trim().split(':')
        if (mika !in enabled) return
        try {
            raw(shell, "settings put secure $key ${enabled.filter { it != mika }.joinToString(":").ifEmpty { "\"\"" }}")
            Thread.sleep(500)
        } finally {
            raw(shell, "settings put secure $key ${enabled.joinToString(":")}")
        }
    }

    /**
     * 以 shell 身份跑一条命令。
     *
     * 走内置助手而不是 `Instrumentation.getUiAutomation().executeShellCommand`:
     * 系统里只能注册一个 UiAutomationService,instrumentation 一旦持有整个测试期间都不放,
     * 助手进程的 UiAutomation(免无障碍读屏那条通道)就会注册不上、一直报
     * `UiAutomationService ... already registered`。测试要验的恰恰是那条通道,所以这里从根上让开。
     */
    fun sh(cmd: String): String = raw(shell(), cmd)

    private fun raw(shell: IMikaShell, cmd: String): String =
        runCatching { shell.runCommand(cmd).getString("output").orEmpty() }.getOrDefault("")

    /** 在虚拟屏上打开测试包里的页面(按类给,类名和清单里登记的一致)。 */
    fun startTestActivity(displayId: Int, activity: Class<out android.app.Activity>) {
        sh("am start --display $displayId -n $TEST_PACKAGE/${activity.name}")
    }

    /**
     * 主屏状态:系统的最上层焦点屏、0 号屏的焦点窗口与焦点 Activity、主屏最上面的任务。
     * Agent 在虚拟屏上做任何事,这些都不应该变。
     */
    fun mainScreenState(shell: IMikaShell): String {
        // 只取需要的行:整份 dumpsys 走 Binder 回来会被助手的输出上限截断,关键几行就没了。
        // grep -m1 提前关管道时 dumpsys 会抱怨 Broken pipe,那行也会混进输出(stderr 被合并),所以丢掉 stderr。
        val topDisplay = sh("dumpsys window 2>/dev/null | grep -m1 'mTopFocusedDisplayId='").trim()
        check(topDisplay.isNotEmpty()) { "mTopFocusedDisplayId not found in dumpsys window" }
        // 0 号屏那一段的焦点窗口与焦点 Activity(下一个 Display: 段之前)
        val section = sh(
            "dumpsys window displays 2>/dev/null | awk '/Display: mDisplayId=0/{f=1} " +
                "f&&/mCurrentFocus=|mFocusedApp=/{print} f&&/mFocusedApp=/{exit}'",
        ).lines().map { it.trim() }
        val focus = section.firstOrNull { it.startsWith("mCurrentFocus=") }
        checkNotNull(focus) { "mCurrentFocus not found for display 0: $section" }
        val focusedApp = section.firstOrNull { it.startsWith("mFocusedApp=") }
            ?.let { Regex("u0 (\\S+)").find(it)?.groupValues?.get(1) }
        return "$topDisplay $focus focusedApp=$focusedApp topTask=${shell.topTaskId(android.view.Display.DEFAULT_DISPLAY)}"
    }

    fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(100)
        }
        return block()
    }
}
