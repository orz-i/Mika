package com.wangzan101.mika.core.uistate

/**
 * 读屏模型、稳定编号、等待与诊断已经在 aau-ui（`aau/aau-ui`，纯 JVM）里，宿主只提供 [ScreenPort] 的实现。
 * 这里保留同名类型别名，Mika 侧的调用点与记录格式一字未改；改名只发生在这一个文件。
 */
typealias UiElement = com.agent.use.ui.UiElement
typealias ScreenSnapshot = com.agent.use.ui.ScreenSnapshot
typealias OpenedElsewhere = com.agent.use.ui.OpenedElsewhere
typealias JumpConfirmSummary = com.agent.use.ui.JumpConfirmSummary
typealias ScreenEvent = com.agent.use.ui.ScreenEvent
typealias VisibleTextLine = com.agent.use.ui.VisibleTextLine
typealias Verification = com.agent.use.ui.Verification
typealias UiWindowTree = com.agent.use.ui.UiWindowTree
typealias UiTreeNode = com.agent.use.ui.UiTreeNode
typealias WebInfo = com.agent.use.ui.WebInfo
typealias PageDialog = com.agent.use.ui.PageDialog
typealias ScreenSig = com.agent.use.ui.ScreenSig
typealias ElementQuery = com.agent.use.ui.ElementQuery
typealias ModelSnapshot = com.agent.use.ui.ModelSnapshot
typealias RefStabilizer = com.agent.use.ui.RefStabilizer
typealias StaleCause = com.agent.use.ui.StaleCause
typealias ScreenState = com.agent.use.ui.ScreenState
typealias ScreenCheck = com.agent.use.ui.ScreenCheck
typealias ScreenFacts = com.agent.use.ui.ScreenFacts
typealias TreeQuality = com.agent.use.ui.TreeQuality
typealias TreeCheck = com.agent.use.ui.TreeCheck
typealias Until = com.agent.use.ui.Until
typealias UntilOutcome = com.agent.use.ui.UntilOutcome
