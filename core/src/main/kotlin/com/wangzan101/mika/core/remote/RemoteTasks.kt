package com.wangzan101.mika.core.remote

/**
 * 任务端口与对外状态都在 `aau-remote` 的 `spi` 包里。
 *
 * 与迁出前唯一的区别：任务状态不再是 Mika 的 `TaskState`，而是协议自己的 9 态枚举
 * （见 [RemoteTaskState]）。Mika 的状态到这套枚举的映射放在 `MikaTaskStates.kt`——
 * 那是 Mika 的领域知识，不该进 AAU。
 */
typealias RemoteTask = com.agent.use.remote.spi.RemoteTask

typealias RemoteTaskDetail = com.agent.use.remote.spi.RemoteTaskDetail

typealias RemoteTaskPage = com.agent.use.remote.spi.RemoteTaskPage

typealias RemoteSteer = com.agent.use.remote.spi.RemoteSteer

typealias RemoteTaskState = com.agent.use.remote.spi.RemoteTaskState

typealias StartOutcome = com.agent.use.remote.spi.StartOutcome

typealias RemoteOwnership = com.agent.use.remote.spi.RemoteOwnership

typealias RemoteStatus = com.agent.use.remote.spi.RemoteStatus

typealias RemoteTaskError = com.agent.use.remote.spi.RemoteTaskError

typealias RemoteTasks = com.agent.use.remote.spi.RemoteTaskPort
