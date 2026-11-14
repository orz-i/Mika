package com.wangzan101.mika.cap.phone.a11y

/** 自有注入手势期间隔离浮层触摸；已统一委托至 `aau-a11y` 的 [com.agent.use.a11y.InjectedGestures]。 */
internal typealias InjectedGestures = com.agent.use.a11y.InjectedGestures

/** 每个窗口都尝试更新；已统一委托至 `aau-a11y` 的 [com.agent.use.a11y.forEachGestureWindow]。 */
internal fun <T> forEachGestureWindow(windows: List<T>, update: (T) -> Unit) =
    com.agent.use.a11y.forEachGestureWindow(windows, update)
