package com.wangzan101.mika.core.remote

/**
 * HTTP 传输层现在在 `aau-remote`（`com.agent.use.remote.http`）。
 *
 * 迁出去的理由：这一层只依赖 JDK，和 Mika 的领域没有关系，别的接入方也要用。
 * Mika 保留同名别名，调用点不用改，一眼也能看出「这块不是我们的代码」。
 */
typealias HttpRequest = com.agent.use.remote.http.HttpRequest

typealias HttpResponse = com.agent.use.remote.http.HttpResponse

typealias HttpProtocolException = com.agent.use.remote.http.HttpProtocolException

typealias MiniHttpServer = com.agent.use.remote.http.MiniHttpServer
