package com.wangzan101.mika.cap.phone.shell

/** 临时路由按 UID 生效，不是按屏。用屏和任务位置约束持有范围，避免一个任务静掉另一个屏。 */
internal class DisplayMediaRoutes(
    private val ownDisplay: (Int) -> Boolean,
    private val tasks: () -> List<Task>?,
    private val dedicatedUid: (String, Int) -> Int?,
    private val playing: (Int) -> Boolean?,
    private val open: (Int) -> AutoCloseable?,
    private val now: () -> Long,
    private val closeFailed: (Throwable) -> Unit = {},
) {
    data class Task(val displayId: Int, val userId: Int?, val packages: Set<String>, val visible: Boolean)

    private data class Target(val displayId: Int, val packageName: String, val userId: Int)
    private class Route(val uid: Int, val sink: AutoCloseable, var deadline: Long?, var closing: Boolean = false)
    private val routes = mutableMapOf<Target, Route>()
    // finishDisplay 可能暂时挪动任务。让正在收尾的屏保留路由到最后，不让其他屏跟着等收尾。
    private val finishing = mutableMapOf<Int?, Int>()

    @Synchronized
    fun setMuted(displayId: Int, packageName: String, userId: Int, muted: Boolean, preparing: Boolean): Boolean {
        val target = Target(displayId, packageName, userId)
        if (!muted) return release(target)
        if (!ownDisplay(displayId) || isFinishing(displayId)) return false
        val rows = tasks()
        if (rows == null || outside(target, rows)) {
            release(target)
            return false
        }
        val here = here(target, rows)
        val current = routes[target]
        if (current != null) {
            if (current.closing) return false
            if (here) {
                current.deadline = null
                return true
            }
            if (preparing && current.deadline?.let { now() < it } == true) return true
            if (!release(target)) return false
        }
        if (!here && !preparing) return false
        val uid = dedicatedUid(packageName, userId) ?: return false
        if (routes.values.any { it.uid == uid }) return false
        // 启动前没有本屏任务时，不能碰用户可能正在后台播放的这同一份 App。
        if (!here && playing(uid) != false) return false
        val sink = open(uid) ?: return false
        routes[target] = Route(uid, sink, if (here) null else now() + LAUNCH_GRACE_MS)
        return true
    }

    /** App 被用户挪走、被关闭、预静音后没真正开出来，或读不到任务位置时，及时还声。 */
    @Synchronized
    fun reconcile(): Boolean {
        if (routes.isEmpty()) return false
        val rows = if (routes.keys.any { !isFinishing(it.displayId) }) tasks() else null
        routes.toList().forEach { (target, route) ->
            if (route.closing) release(target)
            else if (!isFinishing(target.displayId)) {
                when {
                    !ownDisplay(target.displayId) || rows == null || outside(target, rows) -> release(target)
                    here(target, rows) -> route.deadline = null
                    route.deadline?.let { now() < it } != true -> release(target)
                }
            }
        }
        return routes.isNotEmpty()
    }

    @Synchronized
    fun hasRoutes(): Boolean = routes.isNotEmpty()

    fun <T> finish(displayId: Int? = null, block: () -> T): T {
        synchronized(this) { finishing[displayId] = (finishing[displayId] ?: 0) + 1 }
        return try {
            block()
        } finally {
            synchronized(this) {
                routes.keys.filter { displayId == null || it.displayId == displayId }.forEach(::release)
                val left = finishing.getValue(displayId) - 1
                if (left == 0) finishing.remove(displayId) else finishing[displayId] = left
            }
        }
    }

    private fun isFinishing(displayId: Int): Boolean = displayId in finishing || null in finishing

    private fun here(target: Target, rows: List<Task>): Boolean = rows.any {
        it.displayId == target.displayId && it.userId == target.userId && target.packageName in it.packages
    }

    private fun outside(target: Target, rows: List<Task>): Boolean = rows.any {
        it.displayId != target.displayId && (it.visible || it.displayId != 0) &&
            (it.userId == null || it.userId == target.userId) && target.packageName in it.packages
    }

    private fun release(target: Target): Boolean {
        val route = routes[target] ?: return true
        route.closing = true
        return runCatching { route.sink.close() }.onFailure(closeFailed).isSuccess.also {
            if (it) routes.remove(target)
        }
    }

    companion object {
        private const val LAUNCH_GRACE_MS = 8_000L
        private const val USER_RANGE = 100_000

        fun isDedicatedAppUid(uid: Int, userId: Int, packageName: String, owners: Array<String>?): Boolean =
            uid >= 0 && uid / USER_RANGE == userId && uid % USER_RANGE in 10_000..19_999 &&
                owners?.singleOrNull() == packageName
    }
}
