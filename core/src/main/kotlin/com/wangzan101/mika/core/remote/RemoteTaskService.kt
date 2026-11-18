package com.wangzan101.mika.core.remote

/** 两个入口（HTTP 与 MCP）共用的任务用例，实现在 `aau-remote` 的 `TaskUseCases`。 */
typealias RemoteTaskService = com.agent.use.remote.api.TaskUseCases

typealias RemoteWait = com.agent.use.remote.api.RemoteWait

typealias RemoteCancellation = com.agent.use.remote.api.RemoteCancellation

typealias RemoteSteerReceipt = com.agent.use.remote.api.RemoteSteerReceipt
