package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PhoneNetworkProbeAndSkillTest {

    private class ProbeNetworkPort : PhoneNetworkPort {
        override suspend fun waitForResponse(urlPattern: String, timeoutMs: Long): NetworkResponseInfo? = null
        override suspend fun getResponseBody(requestId: String?, urlPattern: String?, asString: Boolean): NetworkResponseBody? = null
        override suspend fun inspectNetwork(limit: Int, filter: String?): List<NetworkRequestSummary> = emptyList()
        override suspend fun probeEbpf(targetPackage: String?, useHeuristicsOnly: Boolean): EbpfProbeReport =
            EbpfProbeReport(
                ok = true,
                targetPackage = targetPackage ?: "com.xingin.xhs",
                uid = 10456,
                arch = "arm64",
                nativeLibraryDir = "/data/app/com.xingin.xhs/lib/arm64",
                apkPaths = listOf("/data/app/com.xingin.xhs/split_config.arm64_v8a.apk"),
                scannedLibrariesCount = 2,
                libraries = listOf(
                    EbpfDiscoveredLib(
                        path = "/data/app/com.xingin.xhs/lib/arm64/libcronet.122.0.0.1.so",
                        name = "libcronet.122.0.0.1.so",
                        inAppDir = true,
                        sslSource = "stripped_heuristic",
                        sslWriteOffset = "0x501000",
                        sslReadOffset = "0x502000",
                        sslSniOffset = "0x503000",
                        cronetSource = "elf_heuristic",
                        cronetCreateOffset = "0x230000",
                        cronetResponseStartedOffset = "0x210000",
                        cronetReadCompletedOffset = "0x211000",
                        cronetSucceededOffset = "0x212000",
                    ),
                ),
                unmatchedAppLibs = listOf("libxlog.so"),
                suggestedProfileJson = """{"com.xingin.xhs":{"replaceBuiltinOffsets":true}}""",
            )
    }

    private class ProbeScreen(val net: PhoneNetworkPort) : ScreenPort by FakeScreen() {
        override val network: PhoneNetworkPort = net
    }

    @Test
    fun phoneProbeEbpfToolReturnsStructuredDiscoveryAndActivatesWithNetworkGroup() = runTest {
        val d = toolDispatcher(ProbeScreen(ProbeNetworkPort()))
        val out = d.execute(Part.ToolCall("p1", "phone_probe_ebpf", """{"targetPackage":"com.xingin.xhs"}""")).content
        assertEquals("true", out["ok"]?.jsonPrimitive?.content)
        assertEquals("com.xingin.xhs", out["targetPackage"]?.jsonPrimitive?.content)
        assertEquals(1, out["libraries"]?.jsonArray?.size)
        assertEquals("libcronet.122.0.0.1.so", out["libraries"]!!.jsonArray[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertTrue(out["suggestedProfileJson"]!!.jsonPrimitive.content.contains("replaceBuiltinOffsets"))
    }

    @Test
    fun networkProbeBundledPythonAnalyzerInspectsElfApkProcAndReadelf() {
        val catalog = GuideCatalog.loadBundled()
        assertTrue("skills/network-probe/scripts/analyze_so.py" in catalog.runnableScripts())
        val skillSource = "skills/network-probe"
        val scriptRel = "scripts/analyze_so.py"
        val scriptFile = File("src/main/resources/$skillSource/$scriptRel")
        assertTrue(scriptFile.isFile)

        val tmpDir = File.createTempFile("net_probe_test", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val elfFile = File(tmpDir, "libcronet.122.0.0.1.so").apply {
            writeBytes(buildSyntheticElf64())
        }
        val apkFile = File(tmpDir, "split_config.arm64_v8a.apk")
        ZipOutputStream(apkFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libcronet.122.0.0.1.so"))
            zos.write(elfFile.readBytes())
            zos.closeEntry()
        }

        val procFile = File(tmpDir, "proc.txt").apply {
            writeText(
                """
                pm path: package:/data/app/com.xingin.xhs/split_config.arm64_v8a.apk
                7f80000000-7f80100000 r-xp 00000000 fd:01 123 /data/app/com.xingin.xhs/split_config.arm64_v8a.apk!/lib/arm64-v8a/libcronet.122.0.0.1.so
                net/udp6
                   0: 0000000000000000FFFF00000100A8C0:9C40 0000000000000000FFFF000008080808:01BB 01 00000000:00000000 00:00000000 00000000 10234 0 12345
                """.trimIndent(),
            )
        }

        val readelfFile = File(tmpDir, "readelf.txt").apply {
            writeText(
                """
                   12: 0000000000123400   128 FUNC    GLOBAL DEFAULT   14 SSL_write
                   13: 0000000000123800   128 FUNC    GLOBAL DEFAULT   14 SSL_read
                   14: 0000000000123c00    64 FUNC    GLOBAL DEFAULT   14 SSL_set_tlsext_host_name
                """.trimIndent(),
            )
        }

        val py = findPythonExecutable()
        val pluginOut = File(tmpDir, "plugin.json")

        val inspectOut = runPython(py, scriptFile, "inspect", elfFile.absolutePath, "--pkg", "com.xingin.xhs", "--out-plugin-json", pluginOut.absolutePath)
        val inspectJson = Json.parseToJsonElement(inspectOut).jsonObject
        val stacks = inspectJson["detectedStacks"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(stacks.contains("boringssl"))
        assertTrue(stacks.contains("cronet"))
        assertEquals("0x1234", inspectJson["exportedNetworkSymbols"]!!.jsonObject["SSL_write"]?.jsonPrimitive?.content)
        assertTrue(pluginOut.isFile)
        assertTrue(pluginOut.readText().contains("\"0x1234\""))

        val apkOut = runPython(py, scriptFile, "inspect-apk", apkFile.absolutePath, "--abi", "arm64-v8a", "--extract-dir", File(tmpDir, "extracted").absolutePath, "--pkg", "com.xingin.xhs")
        val apkJson = Json.parseToJsonElement(apkOut).jsonObject
        assertEquals(1, apkJson["matchedInspected"]!!.jsonArray.size)
        assertTrue(File(tmpDir, "extracted/libcronet.122.0.0.1.so").isFile)

        val procOut = runPython(py, scriptFile, "proc-summary", procFile.absolutePath)
        val procJson = Json.parseToJsonElement(procOut).jsonObject
        assertEquals(1, procJson["udp443QuicSockets"]!!.jsonArray.size)
        assertTrue(procJson["hints"]!!.jsonArray.any { "QUIC" in it.jsonPrimitive.content })

        val reOut = runPython(py, scriptFile, "analyze-readelf", readelfFile.absolutePath, "--lib", "libcronet.122.0.0.1.so", "--pkg", "com.xingin.xhs")
        val reJson = Json.parseToJsonElement(reOut).jsonObject
        assertEquals("0x123400", reJson["matchedFunctions"]!!.jsonObject["SSL_write"]?.jsonPrimitive?.content)
        assertEquals("0x123800", reJson["matchedFunctions"]!!.jsonObject["SSL_read"]?.jsonPrimitive?.content)

        // decode-body subcommand: gzip + Protobuf, Thrift Compact, and Zstd dictId detection
        val pbPayload = byteArrayOf(0x0A, 0x06) + "douyin".toByteArray() + byteArrayOf(0x10, 0x2A)
        val gzPb = ByteArrayOutputStream().apply {
            java.util.zip.GZIPOutputStream(this).use { it.write(pbPayload) }
        }.toByteArray()
        val pbB64File = File(tmpDir, "pb_gz.b64").apply {
            writeText(java.util.Base64.getEncoder().encodeToString(gzPb))
        }
        val decPbJson = Json.parseToJsonElement(runPython(py, scriptFile, "decode-body", pbB64File.absolutePath, "--base64")).jsonObject
        assertEquals("gzip", decPbJson["detectedCompression"]?.jsonPrimitive?.content)
        assertEquals("douyin", decPbJson["protobufDecoded"]!!.jsonObject["1"]?.jsonPrimitive?.content)

        val zstdDictFile = File(tmpDir, "zdict.bin").apply {
            writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x23, 0x0A, 0x00, 0x00, 0x00, 0x01))
        }
        val decZstdJson = Json.parseToJsonElement(runPython(py, scriptFile, "decode-body", zstdDictFile.absolutePath)).jsonObject
        assertEquals("zstd", decZstdJson["detectedCompression"]?.jsonPrimitive?.content)
        assertEquals("0xa", decZstdJson["zstdHeader"]!!.jsonObject["dictIdHex"]?.jsonPrimitive?.content)
    }

    private fun findPythonExecutable(): String {
        val explicit = System.getProperty("mika.python")
        if (!explicit.isNullOrBlank()) return explicit
        for (candidate in listOf("python3", "python")) {
            val ok = runCatching {
                val p = ProcessBuilder(candidate, "--version").redirectErrorStream(true).start()
                p.waitFor() == 0
            }.getOrDefault(false)
            if (ok) return candidate
        }
        return "python3"
    }

    private fun runPython(py: String, script: File, vararg args: String): String {
        val pb = ProcessBuilder(listOf(py, script.absolutePath) + args)
        val proc = pb.start()
        val stdout = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = proc.errorStream.readBytes().toString(Charsets.UTF_8)
        val code = proc.waitFor()
        assertEquals("python script failed ($stderr)", 0, code)
        return stdout
    }

    /** Builds a tiny valid ELF64 little-endian AArch64 image with PT_LOAD, .shstrtab, .dynstr, .dynsym, and .rodata. */
    private fun buildSyntheticElf64(): ByteArray {
        val shstr = "\u0000.shstrtab\u0000.dynstr\u0000.dynsym\u0000.rodata\u0000".toByteArray(Charsets.UTF_8)
        val dynstr = "\u0000SSL_write\u0000SSL_read\u0000SSL_set_tlsext_host_name\u0000".toByteArray(Charsets.UTF_8)
        val rodata = "Cronet/122.0.6261.90\u0000(Ljava/lang/Object;JLjava/lang/String;IZZZIZIIJ)J\u0000ssl_lib.cc\u0000".toByteArray(Charsets.UTF_8)

        val dynsymBuf = ByteBuffer.allocate(4 * 24).order(ByteOrder.LITTLE_ENDIAN)
        // Sym 0: STN_UNDEF
        dynsymBuf.position(24)
        // Sym 1: SSL_write -> 0x1234
        dynsymBuf.putInt(1) // st_name ("SSL_write")
        dynsymBuf.put(0x12.toByte()) // STB_GLOBAL | STT_FUNC
        dynsymBuf.put(0)
        dynsymBuf.putShort(4) // .rodata section index
        dynsymBuf.putLong(0x1234L)
        dynsymBuf.putLong(64L)
        // Sym 2: SSL_read -> 0x5678
        dynsymBuf.putInt(11)
        dynsymBuf.put(0x12.toByte())
        dynsymBuf.put(0)
        dynsymBuf.putShort(4)
        dynsymBuf.putLong(0x5678L)
        dynsymBuf.putLong(64L)
        // Sym 3: SSL_set_tlsext_host_name -> 0x9abc
        dynsymBuf.putInt(20)
        dynsymBuf.put(0x12.toByte())
        dynsymBuf.put(0)
        dynsymBuf.putShort(4)
        dynsymBuf.putLong(0x9abcL)
        dynsymBuf.putLong(32L)
        val dynsym = dynsymBuf.array()

        val phOff = 64
        val dataStart = phOff + 56
        val shstrOff = dataStart
        val dynstrOff = shstrOff + shstr.size
        val dynsymOff = dynstrOff + dynstr.size
        val rodataOff = dynsymOff + dynsym.size
        val shOff = rodataOff + rodata.size
        val totalSize = shOff + 5 * 64

        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        // Elf64_Ehdr
        out.put(0x7F.toByte()); out.put('E'.code.toByte()); out.put('L'.code.toByte()); out.put('F'.code.toByte())
        out.put(2); out.put(1); out.put(1); out.put(0) // ELF64, LE, v1
        out.position(16)
        out.putShort(3) // ET_DYN
        out.putShort(183) // EM_AARCH64
        out.putInt(1) // EV_CURRENT
        out.putLong(0) // e_entry
        out.putLong(phOff.toLong()) // e_phoff
        out.putLong(shOff.toLong()) // e_shoff
        out.putInt(0) // e_flags
        out.putShort(64) // e_ehsize
        out.putShort(56) // e_phentsize
        out.putShort(1) // e_phnum
        out.putShort(64) // e_shentsize
        out.putShort(5) // e_shnum
        out.putShort(1) // e_shstrndx

        // PT_LOAD (vaddr == offset == 0)
        out.position(phOff)
        out.putInt(1) // PT_LOAD
        out.putInt(5) // PF_R | PF_X
        out.putLong(0L)
        out.putLong(0L)
        out.putLong(0L)
        out.putLong(totalSize.toLong())
        out.putLong(totalSize.toLong())
        out.putLong(0x1000L)

        out.position(shstrOff); out.put(shstr)
        out.position(dynstrOff); out.put(dynstr)
        out.position(dynsymOff); out.put(dynsym)
        out.position(rodataOff); out.put(rodata)

        // Section headers
        out.position(shOff + 64)
        // 1: .shstrtab
        writeShdr(out, nameOff = 1, type = 3, addr = shstrOff.toLong(), off = shstrOff.toLong(), size = shstr.size.toLong())
        // 2: .dynstr
        writeShdr(out, nameOff = 11, type = 3, addr = dynstrOff.toLong(), off = dynstrOff.toLong(), size = dynstr.size.toLong())
        // 3: .dynsym (sh_link = 2)
        writeShdr(out, nameOff = 19, type = 11, addr = dynsymOff.toLong(), off = dynsymOff.toLong(), size = dynsym.size.toLong(), link = 2, entSize = 24L)
        // 4: .rodata
        writeShdr(out, nameOff = 27, type = 1, addr = rodataOff.toLong(), off = rodataOff.toLong(), size = rodata.size.toLong())

        return ByteArrayOutputStream().apply { write(out.array()) }.toByteArray()
    }

    private fun writeShdr(
        out: ByteBuffer,
        nameOff: Int,
        type: Int,
        addr: Long,
        off: Long,
        size: Long,
        link: Int = 0,
        entSize: Long = 0L,
    ) {
        out.putInt(nameOff)
        out.putInt(type)
        out.putLong(2L) // SHF_ALLOC
        out.putLong(addr)
        out.putLong(off)
        out.putLong(size)
        out.putInt(link)
        out.putInt(0)
        out.putLong(8L)
        out.putLong(entSize)
    }
}
