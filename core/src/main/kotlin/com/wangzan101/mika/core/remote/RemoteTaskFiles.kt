package com.wangzan101.mika.core.remote

/**
 * 附件面在 `aau-remote`：分块、限额、归属、过期都是协议的一部分，和宿主无关。
 * Mika 只提供 [RemoteTasks.fileAccess] 的实现（见 `PhoneTaskFiles`）。
 */
typealias RemoteFile = com.agent.use.remote.spi.RemoteFile

/** Mika 侧旧名字；协议里叫 `RemoteFile`。 */
typealias TaskFile = com.agent.use.remote.spi.RemoteFile

typealias RemoteTaskFiles = com.agent.use.remote.spi.RemoteFileAccess

typealias RemoteUploads = com.agent.use.remote.spi.RemoteUploads

typealias Upload = com.agent.use.remote.spi.Upload

typealias PendingUploads = com.agent.use.remote.spi.PendingUploads
