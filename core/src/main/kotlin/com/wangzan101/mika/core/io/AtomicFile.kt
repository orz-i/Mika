package com.wangzan101.mika.core.io

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 用临时文件替换目标文件。
 *
 * 直接用 `File.renameTo` 替换一个**已存在**的文件：POSIX 上会安静地覆盖，Windows 上只是返回 `false`。
 * 于是"读出来改一下再存回去"这类操作在开发机上全线报 `rename failed`，看不出是平台差异还是真出错。
 *
 * 这里优先走 `ATOMIC_MOVE`：替换要么整体生效要么完全不动，断电也不会留下半个文件。
 * 平台不接受原子替换时（Windows 明确拒绝 `ATOMIC_MOVE` 与 `REPLACE_EXISTING` 的组合）退到普通替换，
 * 语义仍是"目标最终是新内容"，代价只是一个极短的空窗期。
 *
 * 两种方式都失败时把 `IOException` 抛给调用方，由调用方决定是报错还是重试。
 */
object AtomicFile {
    fun replace(temp: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (e: AtomicMoveNotSupportedException) {
            // 文件系统不支持原子替换（跨挂载点、部分 FUSE），走下面的普通替换
        } catch (e: UnsupportedOperationException) {
            // Windows 拒绝 ATOMIC_MOVE 与 REPLACE_EXISTING 并存，走下面的普通替换
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
