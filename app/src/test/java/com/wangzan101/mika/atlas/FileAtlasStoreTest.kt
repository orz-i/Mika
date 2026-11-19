package com.wangzan101.mika.atlas

import com.wangzan101.mika.core.atlas.AppAtlas
import com.wangzan101.mika.core.atlas.AtlasControl
import com.wangzan101.mika.core.atlas.AtlasCodec
import com.wangzan101.mika.core.atlas.AtlasEdge
import com.wangzan101.mika.core.atlas.AtlasScreen
import com.wangzan101.mika.core.atlas.AtlasShare
import com.wangzan101.mika.core.atlas.AtlasShortcut
import com.wangzan101.mika.core.atlas.ShortcutCandidate
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.KeyStoreException
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class FileAtlasStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val now = 1_762_000_000_000L

    private fun atlasDir() = File(tmp.root, "atlas")
    private fun store(k: SecretKey = key, clock: () -> Long = { now }) =
        FileAtlasStore(atlasDir(), AesGcmKeyCipher { k }, clock)

    private fun sampleAtlas(pkg: String = "com.example.shop", label: String = "示例商城", lastUsedMs: Long = 100L): AppAtlas {
        val home = AtlasScreen(
            sigId = "MainActivity#0011223344556677",
            activity = "$pkg/$pkg.MainActivity",
            shortActivity = "MainActivity",
            fingerprint = 0x0011223344556677L,
            anchors = listOf("首页", "购物车"),
            resourceIds = listOf("tab_home", "tab_cart"),
            title = "商城首页",
            titleSource = AtlasScreen.TITLE_LLM,
            appVersion = "2.1.0",
            hits = 4,
            lastSeenMs = lastUsedMs,
            controls = listOf(
                AtlasControl(Selector("TextView", "购物车", "tab_cart", listOf(500, 900, 700, 980), 0), "底部购物车标签", used = 3, lastUsedMs = lastUsedMs),
            ),
        )
        val cart = AtlasScreen(
            sigId = "CartActivity#1122334455667788",
            activity = "$pkg/$pkg.CartActivity",
            shortActivity = "CartActivity",
            fingerprint = 0x1122334455667788L,
            anchors = listOf("购物车", "去结算"),
            resourceIds = listOf("btn_checkout"),
            title = "购物车页",
            titleSource = AtlasScreen.TITLE_LLM,
            appVersion = "2.1.0",
            hits = 2,
            lastSeenMs = lastUsedMs,
        )
        return AppAtlas(
            packageName = pkg,
            appLabel = label,
            versionsSeen = listOf("2.1.0"),
            screens = listOf(home, cart),
            edges = listOf(
                AtlasEdge(home.sigId, cart.sigId, "phone_tap", home.controls.first().selector, ok = 2, lastOkMs = lastUsedMs, lastAttemptMs = lastUsedMs, appVersion = "2.1.0"),
            ),
            lastUsedMs = lastUsedMs,
        )
    }

    @Test fun encryptedPerPackageRoundTripAndNoPlaintextLeak() {
        val s = store()
        assertTrue(s.listPackages().isEmpty())
        assertNull(s.get("com.example.shop"))

        val shop = sampleAtlas("com.example.shop", "示例商城", 200L)
        val mail = sampleAtlas("com.example.mail", "示例邮箱", 500L)
        s.put(shop)
        s.put(mail)

        val shopFile = File(atlasDir(), "com.example.shop.json")
        assertTrue(shopFile.isFile)
        val rawBytes = shopFile.readBytes()
        val needle = "商城首页".toByteArray(Charsets.UTF_8)
        assertFalse(rawBytes.indices.any { i -> i + needle.size <= rawBytes.size && needle.indices.all { rawBytes[i + it] == needle[it] } })

        val reopened = store()
        assertEquals(shop, reopened.get("com.example.shop"))
        assertEquals(listOf("com.example.mail", "com.example.shop"), reopened.listPackages())
        assertEquals(2, reopened.all().size)
    }

    @Test fun unsafePackageNameSanitizedInsideDirectory() {
        val s = store()
        val weirdPkg = "../evil/pkg"
        val atlas = sampleAtlas(weirdPkg, "Weird", 10L)
        s.put(atlas)
        val files = atlasDir().listFiles().orEmpty().toList()
        assertEquals(1, files.size)
        assertEquals(atlasDir().canonicalPath, files.single().parentFile!!.canonicalPath)
        assertEquals(atlas, s.get(weirdPkg))
    }

    @Test fun corruptOrWrongKeyFileMovedAsideAndTransientKeystoreErrorRethrown() {
        val s = store()
        val shop = sampleAtlas("com.example.shop")
        s.put(shop)
        val target = File(atlasDir(), "com.example.shop.json")
        val original = target.readBytes()

        // 密钥库一时不可用：直接抛出，不动原文件
        val busyCipher = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = throw KeyStoreException("busy")
            override fun decrypt(data: ByteArray) = throw KeyStoreException("busy")
        }
        val busyStore = FileAtlasStore(atlasDir(), busyCipher) { now }
        assertTrue(runCatching { busyStore.get("com.example.shop") }.exceptionOrNull() is KeyStoreException)
        assertTrue(target.exists())
        assertArrayEquals(original, target.readBytes())
        assertNull(busyStore.brokenNotice.value)

        // 密钥失效或内容损坏：另存为 .broken-<stamp> 并发布提示
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val wrongKeyStore = store(otherKey)
        assertNull(wrongKeyStore.get("com.example.shop"))
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(now)
        val broken = File(atlasDir(), "com.example.shop.json.broken-$stamp")
        assertTrue(broken.isFile)
        assertArrayEquals(original, broken.readBytes())
        assertFalse(target.exists())
        assertEquals(broken.name, wrongKeyStore.brokenNotice.value)

        // 后续写入不会覆盖 .broken 文件
        val fresh = sampleAtlas("com.example.shop", lastUsedMs = 999L)
        wrongKeyStore.put(fresh)
        assertArrayEquals(original, broken.readBytes())
        assertEquals(fresh, wrongKeyStore.get("com.example.shop"))
    }

    @Test fun atomicConcurrentUpdatesPreserveAllCounts() {
        val s = store()
        val pkg = "com.example.concurrent"
        s.put(sampleAtlas(pkg, lastUsedMs = 1L))

        val threads = List(4) {
            Thread {
                repeat(25) {
                    s.update(pkg) { cur ->
                        val base = cur!!
                        val first = base.screens.first()
                        base.copy(screens = listOf(first.copy(hits = first.hits + 1)) + base.screens.drop(1))
                    }
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        val updated = s.get(pkg)!!
        assertEquals(4 + 100, updated.screens.first().hits)
        assertTrue(atlasDir().listFiles()?.none { it.name.endsWith(".tmp") } == true)
    }

    @Test fun clearPackageWipeAllAndShareRoundTrip() {
        val s = store()
        val routes = FileRouteStore(tmp.newFolder("routes"))
        val pkg = "com.example.shop"
        s.put(sampleAtlas(pkg, lastUsedMs = 200L))
        routes.put(
            Route(
                id = "r1",
                template = "打开购物车",
                slots = 0,
                packages = listOf(pkg),
                appVersions = mapOf(pkg to "2.1.0"),
                steps = listOf(RouteStep("phone_back", "{}", null, Anchors(pkg, listOf("购物车")), Anchors(pkg, listOf("首页")))),
                lastUsedMs = 200L,
            ),
        )

        val exportedJson = s.exportPackageJson(pkg, routes.routesForPackage(pkg))
        assertNotNull(exportedJson)
        assertTrue(s.clearPackage(pkg))
        assertFalse(s.clearPackage(pkg))
        assertNull(s.get(pkg))
        assertEquals(1, routes.clearPackage(pkg))

        val imported = s.importShare(exportedJson!!, routeStore = routes, conflictMode = AtlasShare.ConflictMode.Merge)
        assertTrue(imported is AtlasShare.ImportResult.Imported)
        assertNotNull(s.get(pkg))
        assertEquals(1, routes.routesForPackage(pkg).size)

        s.wipeAll(deleteKeyAlias = false)
        assertTrue(s.listPackages().isEmpty())
    }

    @Test fun cachedReadsAvoidRepeatedDecryptionAndStayCoherentWithWrites() {
        var decrypts = 0
        val delegate = AesGcmKeyCipher { key }
        val countingCipher = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = delegate.encrypt(plain)
            override fun decrypt(data: ByteArray): ByteArray {
                decrypts++
                return delegate.decrypt(data)
            }
        }
        val pkg = "com.example.cached"
        FileAtlasStore(atlasDir(), delegate) { now }.put(sampleAtlas(pkg, lastUsedMs = 10L))

        val cachedStore = FileAtlasStore(atlasDir(), countingCipher) { now }
        assertNotNull(cachedStore.get(pkg))
        assertEquals(1, decrypts)
        // 后续多次读取命中内存缓存，不再重复解密
        assertNotNull(cachedStore.get(pkg))
        assertNotNull(cachedStore.get(pkg))
        assertEquals(1, decrypts)

        cachedStore.update(pkg) { cur -> cur!!.copy(appLabel = "新名称") }
        assertEquals("新名称", cachedStore.get(pkg)?.appLabel)
        assertEquals(1, decrypts)

        assertTrue(cachedStore.clearPackage(pkg))
        assertNull(cachedStore.get(pkg))
    }

    @Test fun shortcutsAndCandidatesPersistEncryptedAndFilterExport() {
        val s = store()
        val base = sampleAtlas("com.example.shop", lastUsedMs = 300L)
        val steps = listOf(
            RouteStep("phone_tap", """{"element":"购物车"}""", base.screens.first().controls.first().selector, Anchors("com.example.shop", listOf("首页")), Anchors("com.example.shop", listOf("购物车"))),
            RouteStep("phone_back", "{}", null, Anchors("com.example.shop", listOf("购物车")), Anchors("com.example.shop", listOf("首页"))),
        )
        val activeSc = AtlasShortcut(
            id = "sc-active",
            name = "快速进入购物车并返回",
            description = "打开购物车后返回",
            slots = 0,
            entrySigId = base.screens.first().sigId,
            steps = steps,
            successes = 3,
            lastUsedMs = 300L,
        )
        val disabledSc = activeSc.copy(id = "sc-disabled", name = "已停用快捷流程", disabled = true)
        val candidate = ShortcutCandidate(
            signatureKey = "cand-key",
            entrySigId = base.screens.first().sigId,
            steps = steps,
            seenCount = 1,
            lastSeenMs = 300L,
        )
        val withShortcuts = base.copy(shortcuts = listOf(activeSc, disabledSc), candidatePatterns = listOf(candidate))
        s.put(withShortcuts)

        val shopFile = File(atlasDir(), "com.example.shop.json")
        val rawBytes = shopFile.readBytes()
        val needle = "快速进入购物车并返回".toByteArray(Charsets.UTF_8)
        assertFalse(rawBytes.indices.any { i -> i + needle.size <= rawBytes.size && needle.indices.all { rawBytes[i + it] == needle[it] } })

        val reopened = store()
        assertEquals(withShortcuts, reopened.get("com.example.shop"))

        val exportedJson = reopened.exportPackageJson("com.example.shop")!!
        val lint = AtlasShare.decodeAndLint(exportedJson) as AtlasShare.LintResult.Valid
        assertEquals(listOf("sc-active"), lint.share.atlas.shortcuts.map { it.id })
        assertTrue(lint.share.atlas.candidatePatterns.isEmpty())

        // 外部替换磁盘加密文件后 invalidateCache 必须清理内存缓存并重读磁盘
        val revBefore = reopened.changes.value
        store().put(base.copy(appLabel = "磁盘还原后名称"))
        assertEquals("示例商城", reopened.get("com.example.shop")?.appLabel)
        reopened.invalidateCache()
        assertTrue(reopened.changes.value > revBefore)
        assertEquals("磁盘还原后名称", reopened.get("com.example.shop")?.appLabel)
    }

    private fun legacyInputAtlas(): AppAtlas {
        val base = sampleAtlas()
        val selector = Selector("EditText", "蓝牙耳机", "query", listOf(0, 100, 300, 150), 0, true)
        val step = RouteStep("phone_type_text", """{"text":"蓝牙耳机"}""", selector, Anchors(base.packageName, emptyList()), Anchors(base.packageName, emptyList()))
        return base.copy(
            screens = listOf(base.screens.first().copy(controls = listOf(AtlasControl(selector, used = 7)))) + base.screens.drop(1),
            edges = base.edges + AtlasEdge(base.screens.first().sigId, base.screens.first().sigId, "phone_type_text", selector, ok = 5),
            shortcuts = listOf(AtlasShortcut("old", "搜索", entrySigId = base.screens.first().sigId, steps = listOf(step))),
            candidatePatterns = listOf(ShortcutCandidate("old", base.screens.first().sigId, listOf(step))),
        )
    }

    @Test fun legacyPrivateInputsAreMigratedOnReadAndRewrittenEncryptedOnce() {
        val legacy = legacyInputAtlas()
        val cipher = AesGcmKeyCipher { key }
        atlasDir().mkdirs()
        val file = File(atlasDir(), "${legacy.packageName}.json")
        file.writeBytes(cipher.encrypt(AtlasCodec.encode(legacy).toByteArray(Charsets.UTF_8)))

        val migrated = store().get(legacy.packageName)!!
        assertEquals(2, migrated.screens.size)
        assertEquals(7, migrated.screens.first().controls.single().used)
        assertEquals(5, migrated.edges.last().ok)
        assertTrue(migrated.shortcuts.isEmpty())
        assertTrue(migrated.candidatePatterns.isEmpty())
        assertFalse(AtlasCodec.encode(migrated).contains("蓝牙耳机"))
        val encrypted = file.readBytes()
        assertFalse(cipher.decrypt(encrypted).toString(Charsets.UTF_8).contains("蓝牙耳机"))
        assertEquals(migrated, store().get(legacy.packageName))
        assertArrayEquals("第二次读取不再迁移重写", encrypted, file.readBytes())
    }

    @Test fun migrationWriteFailureDoesNotQuarantineOrOverwriteValidSource() {
        val legacy = legacyInputAtlas()
        val cipher = AesGcmKeyCipher { key }
        atlasDir().mkdirs()
        val file = File(atlasDir(), "${legacy.packageName}.json")
        val original = cipher.encrypt(AtlasCodec.encode(legacy).toByteArray(Charsets.UTF_8))
        file.writeBytes(original)
        val failing = object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray = throw IOException("disk unavailable")
            override fun decrypt(data: ByteArray) = cipher.decrypt(data)
        }
        val s = FileAtlasStore(atlasDir(), failing)
        assertTrue(runCatching { s.get(legacy.packageName) }.exceptionOrNull() is IOException)
        assertArrayEquals(original, file.readBytes())
        assertNull(s.brokenNotice.value)
        assertEquals(listOf(file.name), atlasDir().listFiles()!!.map { it.name })
    }

    @Test fun putAndUpdateCleanBothCacheAndDisk() {
        val legacy = legacyInputAtlas()
        val s = store()
        s.put(legacy)
        assertFalse(AtlasCodec.encode(s.get(legacy.packageName)!!).contains("蓝牙耳机"))
        s.update(legacy.packageName) { legacy }
        assertFalse(AtlasCodec.encode(s.get(legacy.packageName)!!).contains("蓝牙耳机"))
        assertEquals(s.get(legacy.packageName), store().get(legacy.packageName))
    }
}
