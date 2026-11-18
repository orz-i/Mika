package com.wangzan101.mika.core.remote

/**
 * 鉴权门口在 `aau-remote` 的 `AccessGate`：Origin 拒绝 → 失败冷却 → 双令牌。
 * 顺序与冷却语义都是踩过坑的硬约束，实现只此一份，Mika 不再维护第二份。
 */
typealias RemoteAccess = com.agent.use.remote.http.AccessGate
