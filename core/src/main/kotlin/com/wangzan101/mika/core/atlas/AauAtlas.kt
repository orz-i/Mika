package com.wangzan101.mika.core.atlas

/**
 * 页面图谱的数据模型（App / 页面 / 边 / 快捷流程 / 候选序列、编解码与淘汰逻辑）在 aau-ui（`aau/aau-ui`），
 * 存储接口 [AtlasStore] 也在那里：落盘、并发锁与本地加密仍由 app 层实现。
 * 这里保留同名别名，并把 Mika 自己的清洗策略挂成扩展函数。
 */
typealias AtlasControl = com.agent.use.ui.AtlasControl
typealias AtlasScreen = com.agent.use.ui.AtlasScreen
typealias AtlasEdge = com.agent.use.ui.AtlasEdge
typealias AtlasShortcut = com.agent.use.ui.AtlasShortcut
typealias ShortcutCandidate = com.agent.use.ui.ShortcutCandidate
typealias AppAtlas = com.agent.use.ui.AppAtlas
typealias AtlasStore = com.agent.use.ui.AtlasStore
typealias InMemoryAtlasStore = com.agent.use.ui.InMemoryAtlasStore

/** 清除旧版本遗留的输入原文，不能安全还原参数的快捷/候选不再使用。 */
fun AppAtlas.privacyCleaned(): AppAtlas = AtlasSanitizer.cleanStoredAtlas(this)

/** 用户手动重命名页面；标题写入 [AtlasScreen.TITLE_USER]，后续模型命名不再覆盖。空标题或敏感文本不生效。 */
fun AppAtlas.withScreenTitle(sigId: String, title: String, nowMs: Long = System.currentTimeMillis()): AppAtlas {
    val clean = AtlasSanitizer.cleanSingleLine(title, AtlasSanitizer.MAX_TITLE_LEN) ?: return this
    return copy(
        screens = screens.map { s ->
            if (s.sigId != sigId) s
            else s.copy(title = clean, titleSource = AtlasScreen.TITLE_USER, lastSeenMs = maxOf(s.lastSeenMs, nowMs))
        },
    )
}
