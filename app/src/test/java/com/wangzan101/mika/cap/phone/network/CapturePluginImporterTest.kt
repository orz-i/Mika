package com.wangzan101.mika.cap.phone.network

import com.agent.use.common.network.plugin.NetworkCapturePluginRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导入插件包：解包 → 校验 → 搬进 `capture_plugins/<id>`。
 *
 * 这些用例都在 JVM 上跑，所以覆盖的是真正的解析与安装逻辑；把 uri 拷成文件那一层
 * （[CapturePluginProfilesScreen] 里的 `stagePickedPack`）只剩 contentResolver 的胶水。
 */
class CapturePluginImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val staging: File get() = File(tempFolder.root, CapturePluginImporter.STAGING_DIR_NAME)

    @After
    fun tearDown() {
        NetworkCapturePluginRegistry.clearJsonProfileOverrides()
    }

    private fun spec(
        id: String = "acme.market",
        version: Int = 1,
        apiVersion: Int = 1,
        name: String = "Acme Market",
        extra: String = "",
    ): String = """
        {
          "id": "$id",
          "version": $version,
          "apiVersion": $apiVersion,
          "packages": ["com.acme.$id"],
          "declaration": { "name": "$name", "notes": ["reads responses"] },
          "responseCodecs": [ { "when": [{"header": "x-acme-enc", "equals": "1"}],
                                "steps": [{"op": "decompress", "algo": "gzip"}] } ]
          $extra
        }
    """.trimIndent()

    private fun zip(name: String, entries: Map<String, String>): File {
        val file = File(tempFolder.root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (path, text) ->
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun stageReady(source: File, name: String = source.name): CapturePluginImporter.Staged {
        val outcome = CapturePluginImporter.stage(source, name, staging)
        assertTrue("stage failed: $outcome", outcome is CapturePluginImporter.Outcome.Ready)
        return (outcome as CapturePluginImporter.Outcome.Ready).staged
    }

    private fun newStore(): CapturePluginProfileStore =
        CapturePluginProfileStore.forDirectory(tempFolder.root, builtinAssetRoot()).also {
            // 内置包要先装再加载，否则下面「内置包不给删」那条会因为压根没注册而假通过。
            it.loadIntoRegistry()
        }

    private fun builtinAssetRoot(): File {
        val candidates = listOf(
            File("../aau/aau-common/src/main/assets/aau_capture_plugins"),
            File("aau/aau-common/src/main/assets/aau_capture_plugins"),
            File("../aau-common/src/main/assets/aau_capture_plugins"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到内置插件包目录: " + candidates.joinToString { it.absolutePath })
    }

    // ------------------------------------------------------------------ 解包

    @Test
    fun zipWithManifestAtItsRootBecomesAPackage() {
        val archive = zip(
            "acme.zip",
            mapOf("plugin.json" to spec(extra = ", \"dex\": \"plugin.dex\""), "plugin.dex" to "not-really-a-dex"),
        )
        val staged = stageReady(archive)

        // id 带 external. 前缀；目录名要用裸 id，重装才会覆盖同一处。
        assertEquals("external.acme.market", staged.info.id)
        assertEquals("acme.market", staged.specId)
        assertEquals("Acme Market", staged.info.title)
        assertEquals(listOf("reads responses"), staged.info.declares)
        assertEquals(1, staged.info.codecs)
        assertTrue(staged.info.hasCode)
        // dex 声明存在但文件不是真 dex；校验只到「文件在不在、sha 对不对」，所以这步仍算通过。
        assertEquals(null, staged.info.error)
    }

    @Test
    fun packageInsideASingleFolderIsFoundAndSiblingsAreDropped() {
        val archive = zip(
            "nested.zip",
            mapOf(
                "acme/plugin.json" to spec(),
                "notes.txt" to "读我",
                "__MACOSX/._acme" to "junk",
            ),
        )
        val staged = stageReady(archive)
        assertEquals("external.acme.market", staged.info.id)
        // 包目录里只有包自己的东西：打包时夹带的说明和 macOS 垃圾不进设备。
        assertEquals(listOf("plugin.json"), staged.dir.list()!!.toList().sorted())
    }

    @Test
    fun aPlainJsonFileIsAPackageToo() {
        val file = File(tempFolder.root, "plugin.json").apply { writeText(spec(id = "solo.pack")) }
        val staged = stageReady(file)
        assertEquals("external.solo.pack", staged.info.id)
    }

    @Test
    fun anArchiveThatEscapesItsFolderIsRejected() {
        val archive = zip("evil.zip", mapOf("plugin.json" to spec(), "../escape.txt" to "x"))
        val outcome = CapturePluginImporter.stage(archive, "evil.zip", staging)
        assertEquals(
            CapturePluginImporter.Reason.UnsafeEntry,
            (outcome as CapturePluginImporter.Outcome.Failed).reason,
        )
        // 失败不留暂存目录：下一次扫描不该看见半个包。
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun twoManifestsInOneArchiveAreAmbiguous() {
        val archive = zip(
            "two.zip",
            mapOf("a/plugin.json" to spec(id = "a.pack"), "b/plugin.json" to spec(id = "b.pack")),
        )
        val outcome = CapturePluginImporter.stage(archive, "two.zip", staging)
        assertEquals(
            CapturePluginImporter.Reason.Ambiguous,
            (outcome as CapturePluginImporter.Outcome.Failed).reason,
        )
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun anArchiveWithoutAManifestIsNotAPackage() {
        val archive = zip("empty.zip", mapOf("readme.txt" to "nothing here"))
        val outcome = CapturePluginImporter.stage(archive, "empty.zip", staging)
        assertEquals(
            CapturePluginImporter.Reason.NotAPackage,
            (outcome as CapturePluginImporter.Outcome.Failed).reason,
        )
    }

    @Test
    fun aPackageThatFailsValidationNeverReachesTheDisk() {
        val archive = zip("old.zip", mapOf("plugin.json" to spec(apiVersion = 99)))
        val outcome = CapturePluginImporter.stage(archive, "old.zip", staging)
        val failed = outcome as CapturePluginImporter.Outcome.Failed
        assertEquals(CapturePluginImporter.Reason.Invalid, failed.reason)
        assertEquals("api_version_mismatch:99!=1", failed.detail)
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun anOversizedFileIsRejectedBeforeItIsRead() {
        val huge = File(tempFolder.root, "huge.zip")
        // 稀疏文件：只改长度，不真占 9 MB。
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(CapturePluginImporter.MAX_PACKAGE_BYTES + 1) }
        val outcome = CapturePluginImporter.stage(huge, "huge.zip", staging)
        assertEquals(
            CapturePluginImporter.Reason.TooLarge,
            (outcome as CapturePluginImporter.Outcome.Failed).reason,
        )
    }

    // ------------------------------------------------------------------ 安装

    @Test
    fun installingPutsThePackageWhereTheLoaderLooks() {
        val store = newStore()
        val staged = stageReady(zip("acme.zip", mapOf("plugin.json" to spec())))

        val result = store.importPack(staged)
        assertTrue("import failed: $result", result is CapturePluginImporter.InstallResult.Installed)
        assertFalse((result as CapturePluginImporter.InstallResult.Installed).replaced)

        val dir = File(store.pluginsDir, "acme.market")
        assertTrue(File(dir, "plugin.json").isFile)
        assertTrue(NetworkCapturePluginRegistry.listPluginIds().contains("external.acme.market"))
        // 装完暂存区就空了，不会在 /data 里留第二份。
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun importingTheSameIdAgainReplacesInsteadOfStacking() {
        val store = newStore()
        store.importPack(stageReady(zip("v1.zip", mapOf("plugin.json" to spec(version = 1, name = "Acme v1")))))
        val second = store.importPack(
            stageReady(zip("v2.zip", mapOf("plugin.json" to spec(version = 2, name = "Acme v2")))),
        )
        assertTrue(second is CapturePluginImporter.InstallResult.Installed)
        assertTrue((second as CapturePluginImporter.InstallResult.Installed).replaced)

        assertEquals(1, store.pluginsDir!!.listFiles()!!.count { it.name == "acme.market" })
        val packs = store.snapshotPacks().filter { it.id == "external.acme.market" }
        assertEquals(1, packs.size)
        assertEquals("Acme v2", packs.single().info.title)
        assertEquals(2, packs.single().info.version)
    }

    @Test
    fun aPackWithoutADeclarationIsListedByItsBareId() {
        val store = newStore()
        val bare = """{ "id": "bare.pack", "apiVersion": 1, "packages": ["com.bare.pack"] }"""
        store.importPack(stageReady(zip("bare.zip", mapOf("plugin.json" to bare))))
        val pack = store.snapshotPacks().first { it.id == "external.bare.pack" }
        assertNull(pack.info.title)
        // 列表里不该出现 `external.` 这个注册表前缀。
        assertEquals("bare.pack", pack.displayTitle)
    }

    @Test
    fun reimportingDoesNotSilentlyTurnAPackBackOn() {
        val store = newStore()
        store.importPack(stageReady(zip("v1.zip", mapOf("plugin.json" to spec(version = 1)))))
        store.setPackEnabled("external.acme.market", false)
        assertFalse(store.snapshotPacks().first { it.id == "external.acme.market" }.enabled)

        store.importPack(stageReady(zip("v2.zip", mapOf("plugin.json" to spec(version = 2)))))
        // 关掉是用户的决定，升级不该替他改回去。
        assertFalse(store.snapshotPacks().first { it.id == "external.acme.market" }.enabled)
        assertTrue(NetworkCapturePluginRegistry.listPluginIds().none { it == "external.acme.market" })
    }

    @Test
    fun onlyExternalPackagesCanBeDeleted() {
        val store = newStore()
        store.importPack(stageReady(zip("acme.zip", mapOf("plugin.json" to spec()))))
        val externalDir = File(store.pluginsDir, "acme.market")
        assertTrue(externalDir.isDirectory)

        assertTrue(store.removePack("external.acme.market"))
        assertFalse(externalDir.exists())
        assertFalse(NetworkCapturePluginRegistry.listPluginIds().contains("external.acme.market"))

        // 内置包在 assets 里，删了下次启动又回来——拒绝，让它走「关掉开关」。
        assertFalse(store.removePack("builtin.bosszhipin"))
        assertNotNull(store.builtinPluginsDir)
        assertTrue(NetworkCapturePluginRegistry.listPluginIds().contains("builtin.bosszhipin"))
    }

    @Test
    fun leftoverStagingFromAKilledProcessIsCleanedUp() {
        val stale = File(staging, "import-dead").apply { mkdirs() }
        File(stale, "unpacked").mkdirs()
        CapturePluginImporter.clearStaging(staging)
        assertTrue(staging.listFiles().isNullOrEmpty())
    }
}
