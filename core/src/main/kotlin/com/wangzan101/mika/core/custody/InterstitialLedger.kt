package com.wangzan101.mika.core.custody

/**
 * 线程安全的中间页账本，同时提供纯函数状态转换入口。
 *
 * 生命周期：`open` -> `Resolved(by=USER|AGENT, choice=ALLOW|DENY|UNKNOWN, at)` -> TTL 清理。
 * - 已处于终态（非 [InterstitialChoice.UNKNOWN]）的条目不会被后续写入覆盖；
 * - 若条目此前以 [InterstitialChoice.UNKNOWN] 解决，则允许后续具体选择（[InterstitialChoice.ALLOW]/[InterstitialChoice.DENY]）或用户操作补充定性；
 * - 读取时按 [nowMs] 过滤已过期条目，[pruneExpired] 负责实际回收存储空间。
 */
class InterstitialLedger(
    val openTtlMs: Long = LedgerEntry.DEFAULT_OPEN_TTL_MS,
    val resolvedTtlMs: Long = LedgerEntry.DEFAULT_RESOLVED_TTL_MS,
    val attributionResolvedWindowMs: Long = LedgerEntry.DEFAULT_RESOLVED_WINDOW_MS,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, LedgerEntry>()

    /** 记录一个新打开的中间页（若同 ID 已存在则重置为 [LedgerState.Open]）。 */
    fun open(interstitial: Interstitial, nowMs: Long = interstitial.openedAtMs): LedgerEntry {
        val entry = openEntry(interstitial, nowMs)
        synchronized(lock) {
            pruneLocked(nowMs)
            entries[interstitial.id] = entry
        }
        return entry
    }

    /** 将指定 [id] 的中间页推进至 [LedgerState.Resolved]；不存在或已过期（传 [atMs] 判定）时返回 null。 */
    fun resolve(
        id: String,
        by: InterstitialActor,
        choice: InterstitialChoice,
        atMs: Long,
    ): LedgerEntry? = synchronized(lock) {
        val current = entries[id] ?: return null
        if (current.isExpired(atMs, openTtlMs, resolvedTtlMs)) {
            entries.remove(id)
            return null
        }
        val next = resolveEntry(current, by, choice, atMs)
        entries[id] = next
        next
    }

    /**
     * 便捷方法：关闭/解决托管屏 [displayId] 上当前仍处于 [LedgerState.Open] 状态的中间页。
     * 若提供 [packageName]，则只匹配宿主包名一致的条目。
     */
    fun resolveOpenForDisplay(
        displayId: Int,
        packageName: String? = null,
        by: InterstitialActor,
        choice: InterstitialChoice,
        atMs: Long,
    ): List<LedgerEntry> = synchronized(lock) {
        pruneLocked(atMs)
        val updated = ArrayList<LedgerEntry>()
        for ((id, current) in entries) {
            if (!current.isOpen) continue
            if (current.displayId != displayId) continue
            if (packageName != null && current.packageName != packageName) continue
            val next = resolveEntry(current, by, choice, atMs)
            entries[id] = next
            updated += next
        }
        updated
    }

    /** 读取单条记录；若指定 [nowMs] 且条目已超出 TTL，则返回 null。 */
    fun get(id: String, nowMs: Long? = null): LedgerEntry? = synchronized(lock) {
        val entry = entries[id] ?: return null
        if (nowMs != null && entry.isExpired(nowMs, openTtlMs, resolvedTtlMs)) return null
        entry
    }

    /** 返回在 [nowMs] 时刻仍有效且符合归因窗口（Open 或 Resolved ≤ [resolvedWindowMs]）的屏 [displayId] 条目，按最新更新时间倒序。 */
    fun activeOrRecentForDisplay(
        displayId: Int,
        nowMs: Long,
        resolvedWindowMs: Long = attributionResolvedWindowMs,
    ): List<LedgerEntry> = synchronized(lock) {
        entries.values
            .asSequence()
            .filter { it.displayId == displayId }
            .filter { !it.isExpired(nowMs, openTtlMs, resolvedTtlMs) }
            .filter { it.matchesAttributionWindow(nowMs, resolvedWindowMs) }
            .sortedWith(ENTRY_RECENCY_COMPARATOR)
            .toList()
    }

    /** 返回在 [nowMs] 时刻全部有效的账本快照（满足 R2 归因窗口的条目），按最新更新时间倒序。 */
    fun activeOrRecent(
        nowMs: Long,
        resolvedWindowMs: Long = attributionResolvedWindowMs,
    ): List<LedgerEntry> = synchronized(lock) {
        entries.values
            .asSequence()
            .filter { !it.isExpired(nowMs, openTtlMs, resolvedTtlMs) }
            .filter { it.matchesAttributionWindow(nowMs, resolvedWindowMs) }
            .sortedWith(ENTRY_RECENCY_COMPARATOR)
            .toList()
    }

    /** 返回账本快照；若提供 [nowMs]，过滤已过期条目但不直接修改底层集合。 */
    fun snapshot(nowMs: Long? = null): List<LedgerEntry> = synchronized(lock) {
        entries.values
            .asSequence()
            .filter { nowMs == null || !it.isExpired(nowMs, openTtlMs, resolvedTtlMs) }
            .toList()
    }

    /** 清理在 [nowMs] 时刻已超出 TTL 的条目，返回被清理的条数。 */
    fun pruneExpired(nowMs: Long): Int = synchronized(lock) {
        pruneLocked(nowMs)
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() = synchronized(lock) { entries.clear() }

    private fun pruneLocked(nowMs: Long): Int {
        val iter = entries.iterator()
        var removed = 0
        while (iter.hasNext()) {
            val (_, entry) = iter.next()
            if (entry.isExpired(nowMs, openTtlMs, resolvedTtlMs)) {
                iter.remove()
                removed++
            }
        }
        return removed
    }

    companion object {
        private val ENTRY_RECENCY_COMPARATOR =
            compareByDescending<LedgerEntry> { it.isOpen }
                .thenByDescending { it.updatedAtMs }
                .thenByDescending { it.interstitial.openedAtMs }

        /** 纯状态机函数：构造新开条目。 */
        fun openEntry(interstitial: Interstitial, nowMs: Long = interstitial.openedAtMs): LedgerEntry =
            LedgerEntry(interstitial = interstitial, state = LedgerState.Open, updatedAtMs = nowMs)

        /** 纯状态机函数：将 [entry] 从 Open 推进至 Resolved，保持终态幂等与先决不可逆（仅允许补全 UNKNOWN）。 */
        fun resolveEntry(
            entry: LedgerEntry,
            by: InterstitialActor,
            choice: InterstitialChoice,
            atMs: Long,
        ): LedgerEntry {
            return when (val current = entry.state) {
                LedgerState.Open -> LedgerEntry(
                    interstitial = entry.interstitial,
                    state = LedgerState.Resolved(by = by, choice = choice, at = atMs),
                    updatedAtMs = atMs,
                )
                is LedgerState.Resolved -> {
                    val canRefine =
                        current.choice == InterstitialChoice.UNKNOWN &&
                            choice != InterstitialChoice.UNKNOWN &&
                            !(current.by == InterstitialActor.USER && by == InterstitialActor.AGENT)
                    if (canRefine) {
                        LedgerEntry(
                            interstitial = entry.interstitial,
                            state = LedgerState.Resolved(by = by, choice = choice, at = atMs),
                            updatedAtMs = atMs,
                        )
                    } else {
                        entry
                    }
                }
            }
        }
    }
}
