package com.wangzan101.mika.cap.phone.shell

import com.agent.use.common.AppAudioKey as AauAppAudioKey

typealias AudioMuteLedger = com.agent.use.common.shell.AudioMuteLedger

/**
 * 一项静音的键。委托给 AAU [com.agent.use.common.AppAudioKey]，保持返回 `Pair<String, Int?>?` 以兼容既有调用与测试。
 */
object AppAudioKey {
    fun of(packageName: String, user: Int?): String = AauAppAudioKey.of(packageName, user)

    fun parse(key: String): Pair<String, Int?>? =
        AauAppAudioKey.parse(key)?.let { it.packageName to it.userId }
}
