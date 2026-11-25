package com.wangzan101.mika.cap.phone.network

import com.agent.use.common.network.plugin.EXTERNAL_PLUGIN_ID_PREFIX
import com.agent.use.common.network.plugin.external.ExternalCapturePluginLoader
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 把用户选中的一个文件变成 `capture_plugins/` 里的一个插件包。
 *
 * 两步，中间隔一次给人看的确认：
 *
 * 1. [stage] 解到 `capture_plugins_staging/<token>/` 并校验，得到 [Staged]。
 *    此时**还没有生效**——包里写了什么、会碰哪些通道，都在这一步读出来给界面显示。
 * 2. [install] 把暂存目录搬进 `capture_plugins/<id>/`。目录名用包自己的 id，
 *    所以再导入同一个包是覆盖升级，不是并排两份。
 *
 * 支持两种来源：`.zip`（包里可以再套一层目录）和单独的 `plugin.json`（纯配置包）。
 * 没做代码签名——这里只保证"路径不越界、大小有上限、接口版本和校验和对得上"，
 * 插件代码与宿主同进程运行这件事本身仍由 `allowCode` 那一侧决定。
 */
object CapturePluginImporter {

    /** 暂存目录名。和 `capture_plugins` 平级，加载器不会扫到它。 */
    const val STAGING_DIR_NAME = "capture_plugins_staging"

    /**
     * 覆盖安装时先落到这个前缀的临时目录再改名。`.` 开头，加载器扫不到；
     * 进程被杀留下的残骸由 [CapturePluginProfileStore.loadIntoRegistry] 清掉。
     */
    const val INSTALL_TMP_PREFIX = ".install-"

    /** 单个包的上限。插件包是配置加一个小 dex，8 MiB 已经很宽松。 */
    const val MAX_PACKAGE_BYTES = 8L * 1024 * 1024
    private const val MAX_ENTRIES = 512
    private const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
    /** 打包时常见的嵌套：`foo/plugin.json`、`foo/bar/plugin.json`。再深就认为不是包。 */
    private const val MAX_SCAN_DEPTH = 3

    /** 失败原因。文案在界面层按语言映射，这里只给稳定的类别。 */
    enum class Reason {
        /** 文件里没有 `plugin.json`。 */
        NotAPackage,

        /** 一个压缩包里有多份 `plugin.json`，不知道该装哪个。 */
        Ambiguous,

        /** 超过 [MAX_PACKAGE_BYTES]，或者压缩包解出来太大。 */
        TooLarge,

        /** 压缩包里有绝对路径或 `..`。 */
        UnsafeEntry,

        /** 读不出来：权限、编码、磁盘。 */
        Unreadable,

        /** 能读出 `plugin.json`，但校验不过（接口版本、dex 缺失、sha256 对不上…）。 */
        Invalid,
    }

    sealed interface Outcome {
        data class Ready(val staged: Staged) : Outcome
        data class Failed(val reason: Reason, val detail: String? = null) : Outcome
    }

    sealed interface InstallResult {
        data class Installed(val id: String, val replaced: Boolean) : InstallResult
        data class Failed(val reason: Reason, val detail: String? = null) : InstallResult
    }

    /**
     * 解出来、校验过、还没装的一个包。
     *
     * [root] 是 inspect 的扫描根（里面只有 [dir] 一项），[info] 是加载器对它的判断。
     */
    data class Staged(
        val root: File,
        val dir: File,
        val info: ExternalCapturePluginLoader.PackInfo,
        /** 来源文件名，只用于界面显示「正在导入 xxx」。 */
        val sourceName: String,
    ) {
        /** `external.acme.market` → `acme.market`：目录名与 id 一致，重装才是覆盖而不是并列两份。 */
        val specId: String get() = info.id.removePrefix(EXTERNAL_PLUGIN_ID_PREFIX)
    }

    /**
     * 解包并校验。[source] 是一个已经落地的文件，[stagingRoot] 是暂存根目录。
     *
     * 失败时会把整个暂存目录清掉，不留半成品给下一次扫描看见。
     */
    fun stage(source: File, sourceName: String, stagingRoot: File): Outcome {
        if (source.length() > MAX_PACKAGE_BYTES) {
            return Outcome.Failed(Reason.TooLarge, "source:${source.length()}")
        }
        val root = File(stagingRoot, "import-" + System.nanoTime().toString(36))
        if (!root.mkdirs()) return Outcome.Failed(Reason.Unreadable, "mkdirs")
        try {
            val unpacked = File(root, "unpacked")
            if (!unpacked.mkdirs()) {
                root.deleteRecursively()
                return Outcome.Failed(Reason.Unreadable, "mkdirs")
            }
            if (looksLikeZip(source)) {
                unzipInto(source, unpacked)?.let { failure ->
                    root.deleteRecursively()
                    return failure
                }
            } else {
                // 不是压缩包就当单份 plugin.json：纯配置包常见这种分发方式。
                source.copyTo(File(unpacked, ExternalCapturePluginLoader.PLUGIN_FILE_NAME), overwrite = true)
            }

            val candidates = packageDirs(unpacked)
            when {
                candidates.isEmpty() -> {
                    root.deleteRecursively()
                    return Outcome.Failed(Reason.NotAPackage)
                }
                candidates.size > 1 -> {
                    root.deleteRecursively()
                    return Outcome.Failed(
                        Reason.Ambiguous,
                        candidates.joinToString(",") { it.name },
                    )
                }
            }

            // 统一成「扫描根下只有一层包目录」，inspect 才有确定的输入。
            val dir = File(root, "pkg")
            val from = candidates.single()
            if (!from.renameTo(dir)) {
                if (!copyTree(from, dir)) {
                    root.deleteRecursively()
                    return Outcome.Failed(Reason.Unreadable, "move")
                }
                from.deleteRecursively()
            }
            // 剩下的顶层杂物（压缩包里夹的 README、__MACOSX）不装。
            unpacked.deleteRecursively()

            val info = ExternalCapturePluginLoader.inspect(
                rootDir = root,
                idPrefix = EXTERNAL_PLUGIN_ID_PREFIX,
            ).firstOrNull()
            if (info == null) {
                root.deleteRecursively()
                return Outcome.Failed(Reason.NotAPackage)
            }
            if (info.error != null) {
                root.deleteRecursively()
                return Outcome.Failed(Reason.Invalid, info.error)
            }
            return Outcome.Ready(Staged(root, dir, info, sourceName))
        } catch (t: Throwable) {
            root.deleteRecursively()
            return Outcome.Failed(Reason.Unreadable, t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * 把暂存的包搬进 [pluginsDir]。同一个 id 已存在时替换（先放到 `.install-<id>` 再换名，
     * 中途失败不会留下半个包——`.` 开头的目录加载器本来就不扫）。
     */
    fun install(staged: Staged, pluginsDir: File): InstallResult {
        val id = staged.specId
        if (id.isBlank() || id.any { it == '/' || it == '\\' } || ".." == id) {
            return InstallResult.Failed(Reason.Invalid, "spec_invalid_id")
        }
        if (!pluginsDir.isDirectory && !pluginsDir.mkdirs()) {
            return InstallResult.Failed(Reason.Unreadable, "mkdirs")
        }
        val target = File(pluginsDir, id)
        val staging = File(pluginsDir, INSTALL_TMP_PREFIX + id)
        if (staging.exists()) staging.deleteRecursively()

        if (!staged.dir.renameTo(staging) && !copyTree(staged.dir, staging)) {
            return InstallResult.Failed(Reason.Unreadable, "copy")
        }
        val replaced = target.isDirectory
        if (replaced && !target.deleteRecursively()) {
            staging.deleteRecursively()
            return InstallResult.Failed(Reason.Unreadable, "replace")
        }
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            return InstallResult.Failed(Reason.Unreadable, "rename")
        }
        staged.root.deleteRecursively()
        return InstallResult.Installed(id, replaced)
    }

    /** 用户取消导入：把暂存目录删掉，别留在那儿占地方。 */
    fun discard(staged: Staged) {
        staged.root.deleteRecursively()
    }

    /** 清扫上一次没走完的导入（进程被杀在确认框上）。 */
    fun clearStaging(stagingRoot: File?) {
        stagingRoot?.listFiles()?.forEach { it.deleteRecursively() }
    }

    // ------------------------------------------------------------------ 内部

    private fun looksLikeZip(file: File): Boolean {
        val head = ByteArray(4)
        val read = file.inputStream().use { it.read(head) }
        if (read < 4) return false
        return head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            ((head[2].toInt() == 3 && head[3].toInt() == 4) ||
                (head[2].toInt() == 5 && head[3].toInt() == 6) ||
                (head[2].toInt() == 7 && head[3].toInt() == 8))
    }

    /**
     * 解压到 [target]。返回 null 表示成功，否则是失败原因。
     *
     * 只做两件事：拒绝越界路径、限制解出来的总量。条目本身不挑扩展名——
     * 一个插件包可以有 json、dex、说明文件，加载器只认它认识的那几个。
     */
    private fun unzipInto(source: File, target: File): Outcome.Failed? {
        val targetRoot = target.canonicalFile
        var total = 0L
        var entries = 0
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                if (++entries > MAX_ENTRIES) return Outcome.Failed(Reason.TooLarge, "entries:$entries")
                val relative = safeRelativePath(entry.name)
                    ?: return Outcome.Failed(Reason.UnsafeEntry, entry.name)
                val out = File(target, relative)
                if (!out.canonicalFile.startsWith(targetRoot)) {
                    return Outcome.Failed(Reason.UnsafeEntry, entry.name)
                }
                out.parentFile?.mkdirs()
                var written = 0L
                out.outputStream().buffered().use { os ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n <= 0) break
                        written += n
                        total += n
                        if (written > MAX_ENTRY_BYTES) return Outcome.Failed(Reason.TooLarge, entry.name)
                        if (total > MAX_PACKAGE_BYTES) return Outcome.Failed(Reason.TooLarge, "total:$total")
                        os.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
        return null
    }

    /** 压缩包里的相对路径；绝对路径、`..`、盘符一律返回 null。 */
    private fun safeRelativePath(name: String): String? {
        val clean = name.replace('\\', '/').trim()
        if (clean.isEmpty() || clean.startsWith("/") || clean.contains(':')) return null
        val parts = clean.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    /** 找所有含 `plugin.json` 的目录，最多下探 [MAX_SCAN_DEPTH] 层。 */
    private fun packageDirs(root: File, depth: Int = 0): List<File> {
        if (File(root, ExternalCapturePluginLoader.PLUGIN_FILE_NAME).isFile) return listOf(root)
        if (depth >= MAX_SCAN_DEPTH) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "__MACOSX" }
            ?.sortedBy { it.name }
            ?.flatMap { packageDirs(it, depth + 1) }
            .orEmpty()
    }

    private fun copyTree(from: File, to: File): Boolean {
        if (!to.mkdirs() && !to.isDirectory) return false
        val children = from.listFiles().orEmpty()
        for (child in children) {
            val dest = File(to, child.name)
            if (child.isDirectory) {
                if (!copyTree(child, dest)) return false
            } else {
                try {
                    child.copyTo(dest, overwrite = true)
                } catch (t: Throwable) {
                    return false
                }
            }
        }
        return true
    }
}
