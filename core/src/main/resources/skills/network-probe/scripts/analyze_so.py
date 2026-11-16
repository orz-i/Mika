# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""On-device ELF/APK network stack & offset analyzer for Mika eBPF capture plugins.

Usage:
  analyze_so.py inspect SO_PATH [--pkg PKG] [--out-plugin-json OUT.json]
  analyze_so.py inspect-apk APK_PATH [--abi arm64-v8a] [--lib-pattern REGEX] [--extract-dir DIR] [--pkg PKG] [--out-plugin-json OUT.json]
  analyze_so.py proc-summary SNAPSHOT_TXT
  analyze_so.py analyze-readelf READELF_TXT [--lib LIB_NAME] [--pkg PKG] [--out-plugin-json OUT.json]
  analyze_so.py decode-body BODY_FILE [--base64] [--charset utf-8]

Runs with Python 3 standard library only inside the Mika sandbox (/workspace).
"""
import argparse
import base64
import json
import math
import os
import re
import struct
import sys
import tempfile
import zipfile
import zlib


ELF_MAGIC = b"\x7fELF"
SHT_SYMTAB = 2
SHT_STRTAB = 3
SHT_DYNSYM = 11
PT_LOAD = 1
EM_AARCH64 = 183

INTERESTING_PREFIXES = (
    "SSL_", "BIO_", "PR_", "Cronet_", "ngtcp2_", "lsquic_", "tquic_", "xquic_", "quiche_"
)

STACK_FINGERPRINTS = {
    "boringssl": [b"ssl_lib.cc", b"ssl_lib.c", b"tls_write_app_data", b"BoringSSL", b"SSL_set_tlsext_host_name"],
    "cronet": [b"CronetURLRequest", b"Cronet/", b"org/chromium/net/impl/CronetUrlRequest"],
    "quic_http3": [b"ngtcp2", b"lsquic", b"tquic", b"xquic", b"quiche", b"QuicChromium", b"QUIC"],
    "ttnet_bytedance": [b"ttquic", b"ttboringssl", b"libttnet", b"tt_zstd_manager", b"ttnet_zstd", b"com/ttnet/org/chromium"],
    "mars_tencent": [b"mars::", b"MicroMsg", b"mmtls"],
    "okhttp_conscrypt": [b"okhttp", b"conscrypt", b"Conscrypt"],
    "grpc_protobuf": [b"grpc", b"protobuf", b"application/grpc"],
}


def fail(msg, code=2):
    print(f"analyze_so.py: {msg}", file=sys.stderr)
    sys.exit(code)


def parse_elf64_segments_and_sections(data):
    if len(data) < 64 or data[:4] != ELF_MAGIC:
        return None
    ei_class = data[4]
    ei_data = data[5]
    if ei_class != 2 or ei_data != 1:
        return {"elfClass": ei_class, "littleEndian": ei_data == 1, "supported": False}

    e_machine, _e_ver, _e_entry, e_phoff, e_shoff, _e_flags, _e_ehsize, e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx = struct.unpack_from(
        "<HIQQQIHHHHHH", data, 18
    )

    phdrs = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if off + 56 > len(data):
            break
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, _p_align = struct.unpack_from("<IIQQQQQQ", data, off)
        if p_type == PT_LOAD and p_filesz > 0:
            phdrs.append((p_vaddr, p_vaddr + p_filesz, p_offset))

    def vaddr_to_file_offset(vaddr):
        for va_start, va_end, p_offset in phdrs:
            if va_start <= vaddr < va_end:
                return vaddr - va_start + p_offset
        return vaddr

    sections = []
    shstr = b""
    shdrs_raw = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        if off + 64 > len(data):
            break
        sh = struct.unpack_from("<IIQQQQIIQQ", data, off)
        shdrs_raw.append(sh)
    if 0 <= e_shstrndx < len(shdrs_raw):
        sh_off = shdrs_raw[e_shstrndx][4]
        sh_sz = shdrs_raw[e_shstrndx][5]
        shstr = data[sh_off:sh_off + sh_sz]

    def read_cstr(blob, idx):
        if idx < 0 or idx >= len(blob):
            return ""
        end = blob.find(b"\x00", idx)
        if end < 0:
            end = len(blob)
        return blob[idx:end].decode("utf-8", errors="replace")

    for sh_name_off, sh_type, sh_flags, sh_addr, sh_offset, sh_size, sh_link, _sh_info, _sh_align, sh_entsize in shdrs_raw:
        sections.append({
            "name": read_cstr(shstr, sh_name_off),
            "type": sh_type,
            "flags": sh_flags,
            "addr": sh_addr,
            "offset": sh_offset,
            "size": sh_size,
            "link": sh_link,
            "entsize": sh_entsize,
        })

    symbols = {}
    for sec in sections:
        if sec["type"] not in (SHT_DYNSYM, SHT_SYMTAB):
            continue
        link = sec["link"]
        if link < 0 or link >= len(sections):
            continue
        str_sec = sections[link]
        str_blob = data[str_sec["offset"]:str_sec["offset"] + str_sec["size"]]
        entsize = sec["entsize"] or 24
        count = sec["size"] // entsize
        base = sec["offset"]
        for i in range(count):
            pos = base + i * entsize
            if pos + 24 > len(data):
                break
            st_name, _st_info, _st_other, st_shndx, st_value, _st_size = struct.unpack_from("<IBBHQQ", data, pos)
            if st_shndx == 0 or st_value == 0:
                continue
            sym_name = read_cstr(str_blob, st_name)
            if sym_name and sym_name not in symbols:
                symbols[sym_name] = vaddr_to_file_offset(st_value)

    return {
        "supported": True,
        "elfClass": 64,
        "machine": "aarch64" if e_machine == EM_AARCH64 else f"em_{e_machine}",
        "isAarch64": e_machine == EM_AARCH64,
        "sections": sections,
        "symbols": symbols,
        "vaddr_to_file_offset": vaddr_to_file_offset,
    }


def scan_aarch64_string_xrefs(data, sections, target_strings):
    text_sec = next((s for s in sections if s["name"] == ".text"), None)
    rodata_sec = next((s for s in sections if s["name"] == ".rodata"), None)
    if not text_sec or not rodata_sec:
        return {}

    ro_off = rodata_sec["offset"]
    ro_sz = rodata_sec["size"]
    ro_va = rodata_sec["addr"]
    ro_blob = data[ro_off:ro_off + ro_sz]

    target_vas = {}
    for s in target_strings:
        needle = s.encode("utf-8") + b"\x00"
        idx = ro_blob.find(needle)
        if idx < 0:
            idx = data.find(needle)
            if idx >= 0:
                target_vas[s] = idx if ro_va == ro_off else None
        else:
            target_vas[s] = ro_va + idx

    wanted = {va: name for name, va in target_vas.items() if va is not None}
    if not wanted:
        return {}

    wanted_pages = {va & ~0xFFF: (va, name) for va, name in wanted.items()}
    xrefs = {name: [] for name in wanted.values()}

    t_off = text_sec["offset"]
    t_sz = text_sec["size"]
    t_va = text_sec["addr"]
    # ADRP into any reg + immediate ADD matching exact string VA within next 6 instructions
    limit = min(t_sz, 24 * 1024 * 1024)
    for pos in range(0, limit - 4, 4):
        insn = struct.unpack_from("<I", data, t_off + pos)[0]
        if (insn & 0x9F000000) != 0x90000000:
            continue
        immlo = (insn >> 29) & 0x3
        immhi = (insn >> 5) & 0x7FFFF
        imm = (immhi << 2) | immlo
        if imm & (1 << 20):
            imm -= 1 << 21
        pc = t_va + pos
        page = (pc & ~0xFFF) + (imm << 12)
        if page not in wanted_pages:
            continue
        rd = insn & 0x1F
        for nxt in range(1, 7):
            npos = pos + nxt * 4
            if npos + 4 > limit:
                break
            ni = struct.unpack_from("<I", data, t_off + npos)[0]
            # ADD Xd, Xn, #imm12 (no shift / shift=1)
            if (ni & 0xFF800000) == 0x91000000:
                rn = (ni >> 5) & 0x1F
                if rn == rd:
                    imm12 = (ni >> 10) & 0xFFF
                    shift = (ni >> 22) & 0x3
                    if shift == 1:
                        imm12 <<= 12
                    target_va = page + imm12
                    if target_va in wanted:
                        name = wanted[target_va]
                        if len(xrefs[name]) < 8:
                            xrefs[name].append(hex(t_off + pos))
                        break
    return {k: v for k, v in xrefs.items() if v}


def inspect_so_file(path, pkg=None):
    with open(path, "rb") as f:
        data = f.read()

    lib_name = os.path.basename(path)
    parsed = parse_elf64_segments_and_sections(data)
    if parsed is None:
        return {"path": path, "name": lib_name, "error": "not_an_elf_file"}
    if not parsed.get("supported"):
        return {"path": path, "name": lib_name, "elfClass": parsed.get("elfClass"), "error": "unsupported_non_elf64_le"}

    syms = parsed["symbols"]
    exported_net = {}
    for sym_name, off in syms.items():
        if sym_name.startswith(INTERESTING_PREFIXES) or any(
            k in sym_name for k in ("SSL_write", "SSL_read", "SSL_set_tlsext_host_name", "Cronet")
        ):
            exported_net[sym_name] = hex(off)

    cronet_versions = sorted(set(
        m.decode("utf-8", errors="replace")
        for m in re.findall(rb"(?:Cronet|Chrome)/\d+\.\d+\.\d+\.\d+", data)
    ))[:10]
    jni_sigs = sorted(set(
        m.decode("utf-8", errors="replace")
        for m in re.findall(rb"\(Ljava/lang/Object;JLjava/lang/String;[A-Za-z0-9_;/$]+\)[A-Z]", data)
    ))[:8]

    stacks = []
    for stack_name, needles in STACK_FINGERPRINTS.items():
        if any(n in data for n in needles):
            stacks.append(stack_name)

    xrefs = {}
    if parsed["isAarch64"]:
        xref_targets = ["SSL_write", "SSL_read", "SSL_set_tlsext_host_name"] + jni_sigs[:3]
        xrefs = scan_aarch64_string_xrefs(data, parsed["sections"], xref_targets)

    w_off = syms.get("SSL_write")
    r_off = syms.get("SSL_read")
    sni_off = syms.get("SSL_set_tlsext_host_name", 0)

    suggested = None
    if pkg and w_off and r_off:
        suggested = {
            pkg: {
                "replaceBuiltinOffsets": True,
                "sslOffsetRules": [
                    {
                        "libSubstring": lib_name,
                        "writeOffset": hex(w_off),
                        "readOffset": hex(r_off),
                        "sniOffset": hex(sni_off),
                    }
                ],
            }
        }
        if jni_sigs:
            suggested[pkg]["cronetAbiRule"] = {
                "rawHeadersOffset": "0x20",
                "ioBufferDataOffset": "0x10",
                "stringLayoutMode": 0,
                "vtableSlots": [3, 4, 5],
                "jniSignatures": jni_sigs,
            }

    return {
        "path": path,
        "name": lib_name,
        "sizeBytes": len(data),
        "machine": parsed["machine"],
        "detectedStacks": stacks,
        "cronetVersions": cronet_versions,
        "jniSignatures": jni_sigs,
        "exportedNetworkSymbols": dict(list(exported_net.items())[:80]),
        "aarch64StringXrefs": xrefs,
        "suggestedPluginProfile": suggested,
    }


def cmd_inspect(args):
    if not os.path.isfile(args.so_path):
        fail(f"file not found: {args.so_path}")
    report = inspect_so_file(args.so_path, pkg=args.pkg)
    if args.out_plugin_json and report.get("suggestedPluginProfile"):
        with open(args.out_plugin_json, "w", encoding="utf-8") as f:
            json.dump(report["suggestedPluginProfile"], f, indent=2, ensure_ascii=False)
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


def cmd_inspect_apk(args):
    if not os.path.isfile(args.apk_path):
        fail(f"file not found: {args.apk_path}")
    net_pat = re.compile(args.lib_pattern or r"(cronet|ssl|tls|quic|http|net|okhttp|mars|xlog|curl|grpc|conscrypt)", re.I)
    prefix = f"lib/{args.abi}/"
    extract_root = args.extract_dir
    tmp_ctx = None
    if extract_root:
        os.makedirs(extract_root, exist_ok=True)
    else:
        tmp_ctx = tempfile.TemporaryDirectory(prefix="mika_apk_")
        extract_root = tmp_ctx.name

    try:
        with zipfile.ZipFile(args.apk_path, "r") as zf:
            all_libs = [n for n in zf.namelist() if n.startswith(prefix) and n.endswith(".so")]
            matched = [n for n in all_libs if net_pat.search(os.path.basename(n))]
            inspected = []
            merged_plugins = {}
            for member in matched:
                out_path = os.path.join(extract_root, os.path.basename(member))
                with zf.open(member) as src, open(out_path, "wb") as dst:
                    while True:
                        chunk = src.read(262144)
                        if not chunk:
                            break
                        dst.write(chunk)
                res = inspect_so_file(out_path, pkg=args.pkg)
                inspected.append(res)
                if args.pkg and res.get("suggestedPluginProfile"):
                    merged_plugins.update(res["suggestedPluginProfile"])

        if args.out_plugin_json and merged_plugins:
            with open(args.out_plugin_json, "w", encoding="utf-8") as f:
                json.dump(merged_plugins, f, indent=2, ensure_ascii=False)

        out = {
            "apkPath": args.apk_path,
            "abi": args.abi,
            "totalAbiLibraries": len(all_libs),
            "allLibraryNames": [os.path.basename(n) for n in sorted(all_libs)],
            "matchedInspected": inspected,
            "suggestedPluginProfile": merged_plugins or None,
        }
        print(json.dumps(out, indent=2, ensure_ascii=False))
        return 0
    finally:
        if tmp_ctx is not None:
            tmp_ctx.cleanup()


def decode_proc_ip_port(hex_addr_port):
    if ":" not in hex_addr_port:
        return hex_addr_port, -1
    ip_hex, port_hex = hex_addr_port.split(":", 1)
    try:
        port = int(port_hex, 16)
    except ValueError:
        return hex_addr_port, -1
    if len(ip_hex) == 8:
        raw = bytes.fromhex(ip_hex)
        ip = ".".join(str(b) for b in reversed(raw))
        return f"{ip}:{port}", port
    if len(ip_hex) == 32:
        words = [bytes.fromhex(ip_hex[i:i + 8])[::-1] for i in range(0, 32, 8)]
        raw16 = b"".join(words)
        if raw16[:12] == b"\x00" * 10 + b"\xff\xff":
            ip = ".".join(str(b) for b in raw16[12:16])
        else:
            groups = [raw16[i:i + 2].hex().lstrip("0") or "0" for i in range(0, 16, 2)]
            ip = ":".join(groups)
        return f"[{ip}]:{port}", port
    return hex_addr_port, port


def cmd_proc_summary(args):
    if not os.path.isfile(args.snapshot_txt):
        fail(f"file not found: {args.snapshot_txt}")
    with open(args.snapshot_txt, "r", encoding="utf-8", errors="replace") as f:
        lines = f.read().splitlines()

    app_libs = []
    sys_libs = []
    seen_libs = set()
    udp_443 = []
    tcp_443 = []
    other_remotes = []
    in_udp = False
    in_tcp = False

    for line in lines:
        s = line.strip()
        lower = s.lower()
        if "net/udp" in lower or lower.startswith("udp"):
            in_udp, in_tcp = True, False
        elif "net/tcp" in lower or lower.startswith("tcp"):
            in_tcp, in_udp = True, False

        m_lib = re.search(r"(/[^\s]+\.so)\b", s)
        if m_lib:
            lib = m_lib.group(1)
            if lib not in seen_libs:
                seen_libs.add(lib)
                if lib.startswith(("/system/", "/vendor/", "/apex/", "/product/", "/system_ext/")):
                    sys_libs.append(lib)
                else:
                    app_libs.append(lib)

        m_proc = re.match(r"^\d+:\s+([0-9A-Fa-f]+:[0-9A-Fa-f]{4})\s+([0-9A-Fa-f]+:[0-9A-Fa-f]{4})\s+([0-9A-Fa-f]{2})\b", s)
        if m_proc:
            local_ep, _lport = decode_proc_ip_port(m_proc.group(1))
            rem_ep, rport = decode_proc_ip_port(m_proc.group(2))
            state = m_proc.group(3)
            if rport == 443 and in_udp:
                udp_443.append({"local": local_ep, "remote": rem_ep, "state": state})
            elif rport == 443:
                tcp_443.append({"local": local_ep, "remote": rem_ep, "state": state})
            elif rport > 0:
                other_remotes.append({"proto": "udp" if in_udp else "tcp", "remote": rem_ep, "state": state})

    hints = []
    if udp_443:
        hints.append("Detected UDP:443 active sockets (likely QUIC/HTTP3). Ensure Cronet response hooks or BoringSSL SSL_write/SSL_read are attached rather than TCP-only hooks.")
    if any(".apk!/" in p for p in app_libs):
        hints.append("App maps native .so directly inside split/base APK (!/lib/...). Use `inspect-apk` against `pm path <pkg>` APK paths if nativeLibraryDir is empty.")

    out = {
        "appMappedLibraries": app_libs,
        "systemMappedLibrariesCount": len(sys_libs),
        "udp443QuicSockets": udp_443[:40],
        "tcp443HttpsSockets": tcp_443[:40],
        "otherActiveRemotesCount": len(other_remotes),
        "hints": hints,
    }
    print(json.dumps(out, indent=2, ensure_ascii=False))
    return 0


def cmd_analyze_readelf(args):
    if not os.path.isfile(args.readelf_txt):
        fail(f"file not found: {args.readelf_txt}")
    with open(args.readelf_txt, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()

    found = {}
    for line in text.splitlines():
        m = re.match(r"^\s*\d+:\s*([0-9A-Fa-f]+)\s+\d+\s+FUNC\s+.*?\s+(\S+)$", line)
        if not m:
            continue
        val = int(m.group(1), 16)
        name = m.group(2).split("@@")[0].split("@")[0]
        if val > 0 and (name.startswith(INTERESTING_PREFIXES) or "SSL_" in name or "Cronet" in name):
            found[name] = val

    w = found.get("SSL_write")
    r = found.get("SSL_read")
    sni = found.get("SSL_set_tlsext_host_name", 0)
    suggested = None
    if args.pkg and args.lib and w and r:
        suggested = {
            args.pkg: {
                "replaceBuiltinOffsets": True,
                "sslOffsetRules": [
                    {
                        "libSubstring": args.lib,
                        "writeOffset": hex(w),
                        "readOffset": hex(r),
                        "sniOffset": hex(sni),
                    }
                ],
            }
        }
        if args.out_plugin_json:
            with open(args.out_plugin_json, "w", encoding="utf-8") as f:
                json.dump(suggested, f, indent=2, ensure_ascii=False)

    print(json.dumps({
        "matchedFunctions": {k: hex(v) for k, v in found.items()},
        "note": "readelf Ndx/Value reports ELF virtual address; verify p_vaddr == p_offset (standard ET_DYN) or cross-check with `inspect`.",
        "suggestedPluginProfile": suggested,
    }, indent=2, ensure_ascii=False))
    return 0


def _shannon_entropy(data):
    if not data:
        return 0.0
    counts = [0] * 256
    for b in data:
        counts[b] += 1
    n = float(len(data))
    ent = 0.0
    for c in counts:
        if c:
            p = c / n
            ent -= p * math.log2(p)
    return round(ent, 3)


def _inspect_zstd_header(data):
    if len(data) < 5 or data[:4] != b"\x28\xb5\x2f\xfd":
        return None
    desc = data[4]
    single_seg = (desc >> 5) & 1
    win_len = 0 if single_seg else 1
    dict_flag = desc & 0x03
    dict_len = {0: 0, 1: 1, 2: 2, 3: 4}[dict_flag]
    dict_id = None
    off = 5 + win_len
    if dict_len > 0 and len(data) >= off + dict_len:
        dict_id = int.from_bytes(data[off : off + dict_len], "little")
    return {
        "hasDictionaryId": dict_len > 0,
        "dictId": dict_id,
        "dictIdHex": hex(dict_id) if dict_id is not None else None,
    }


def _clean_text(data, charset="utf-8"):
    try:
        s = data.decode(charset, errors="strict")
    except Exception:
        return None
    bad = sum(1 for ch in s if (ord(ch) < 0x20 and ch not in "\r\n\t") or ord(ch) == 0x7F or ch == "\ufffd")
    if s and (bad / len(s)) > 0.05:
        return None
    return s


def _pb_read_varint(data, pos):
    res = 0
    shift = 0
    for i in range(10):
        if pos >= len(data):
            return None
        b = data[pos]
        pos += 1
        res |= (b & 0x7F) << shift
        if not (b & 0x80):
            return res, pos
        shift += 7
    return None


def _try_parse_protobuf(data, start=0, end=None, depth=0):
    if end is None:
        end = len(data)
    if depth > 16 or start > end:
        return None
    fields = {}
    pos = start
    cnt = 0
    while pos < end:
        cnt += 1
        if cnt > 10000:
            return None
        rv = _pb_read_varint(data, pos)
        if not rv:
            return None
        tag, pos = rv
        if tag <= 0:
            return None
        fnum = tag >> 3
        wtype = tag & 7
        if fnum <= 0 or fnum > 536870911:
            return None
        if wtype == 0:
            rv = _pb_read_varint(data, pos)
            if not rv:
                return None
            val, pos = rv
        elif wtype == 1:
            if end - pos < 8:
                return None
            val = int.from_bytes(data[pos : pos + 8], "little")
            pos += 8
        elif wtype == 5:
            if end - pos < 4:
                return None
            val = int.from_bytes(data[pos : pos + 4], "little")
            pos += 4
        elif wtype == 2:
            rv = _pb_read_varint(data, pos)
            if not rv:
                return None
            ln, pos = rv
            if ln < 0 or ln > (end - pos):
                return None
            sub = data[pos : pos + ln]
            pos += ln
            txt = _clean_text(sub, "utf-8") if ln > 0 else ""
            if txt is not None:
                val = txt
            else:
                nested = _try_parse_protobuf(sub, 0, len(sub), depth + 1)
                val = nested if nested is not None else {"_bytes": len(sub), "hex": sub[:24].hex()}
        else:
            return None
        key = str(fnum)
        if key in fields:
            if not isinstance(fields[key], list):
                fields[key] = [fields[key]]
            fields[key].append(val)
        else:
            fields[key] = val
    return fields if pos == end and fields else None


def _try_parse_thrift_compact(data):
    pos = [0]
    n = len(data)
    def uvar():
        r, s = 0, 0
        for _ in range(10):
            if pos[0] >= n:
                return None
            b = data[pos[0]]; pos[0] += 1
            r |= (b & 0x7F) << s
            if not (b & 0x80):
                return r
            s += 7
        return None
    def zz(v):
        return (v >> 1) ^ -(v & 1)
    def read_elem(t, d):
        if d > 16:
            return None
        if t == 3:
            if pos[0] >= n: return None
            v = struct.unpack("b", data[pos[0]:pos[0]+1])[0]; pos[0] += 1; return v
        if t in (4, 5, 6):
            v = uvar(); return None if v is None else zz(v)
        if t == 7:
            if pos[0] + 8 > n: return None
            v = struct.unpack("<d", data[pos[0]:pos[0]+8])[0]; pos[0] += 8; return v
        if t == 8:
            ln = uvar()
            if ln is None or ln < 0 or pos[0] + ln > n: return None
            sub = data[pos[0]:pos[0]+ln]; pos[0] += ln
            txt = _clean_text(sub, "utf-8")
            return txt if txt is not None else {"_bytes": ln, "hex": sub[:24].hex()}
        if t == 12:
            return read_struct(d + 1)
        if t in (9, 10):
            if pos[0] >= n: return None
            st = data[pos[0]]; pos[0] += 1
            sz, et = (st >> 4) & 0xF, st & 0xF
            if sz == 15:
                sz = uvar()
            if sz is None or sz > 4096 or et < 1 or et > 12: return None
            items = []
            for _ in range(sz):
                if et in (1, 2):
                    if pos[0] >= n: return None
                    b = data[pos[0]]; pos[0] += 1
                    if b not in (0, 1): return None
                    items.append(b == 1)
                else:
                    el = read_elem(et, d + 1)
                    if el is None: return None
                    items.append(el)
            return items
        return None
    def read_struct(d):
        if d > 16:
            return None
        out = {}
        last_id = 0
        while True:
            if pos[0] >= n: return None
            h = data[pos[0]]; pos[0] += 1
            if h == 0:
                return out
            mod, ct = (h >> 4) & 0xF, h & 0xF
            if ct < 1 or ct > 12: return None
            if mod == 0:
                v = uvar()
                if v is None: return None
                fid = zz(v)
            else:
                fid = last_id + mod
            if fid < 0 or fid > 32767: return None
            last_id = fid
            if ct == 1:
                out[str(fid)] = True
            elif ct == 2:
                out[str(fid)] = False
            else:
                val = read_elem(ct, d)
                if val is None: return None
                out[str(fid)] = val
    res = read_struct(0)
    return res if res and pos[0] == n else None


def cmd_decode_body(args):
    if not os.path.isfile(args.body_file):
        fail(f"file not found: {args.body_file}")
    with open(args.body_file, "rb") as f:
        raw = f.read()

    if args.base64:
        try:
            raw = base64.b64decode(raw.strip())
        except Exception as e:
            fail(f"invalid base64 input: {e}")
    else:
        # Auto-detect pure single-line base64 dumps pasted from phone_get_network_response(asString=false)
        stripped = raw.strip()
        if stripped and all(32 <= b < 127 for b in stripped) and not stripped.startswith((b"{", b"[", b"<")):
            if len(stripped) >= 16 and re.fullmatch(rb"[A-Za-z0-9+/]+={0,2}", stripped):
                try:
                    raw = base64.b64decode(stripped, validate=True)
                except Exception:
                    pass

    zstd_info = _inspect_zstd_header(raw)
    decompressed = raw
    compression = None
    if raw.startswith(b"\x1f\x8b"):
        compression = "gzip"
        try:
            decompressed = zlib.decompress(raw, wbits=16 + zlib.MAX_WBITS)
        except Exception as e:
            compression = f"gzip_error:{e}"
    elif len(raw) >= 2 and (raw[0] & 0x0F) == 8 and (((raw[0] << 8) | raw[1]) % 31 == 0):
        compression = "zlib"
        try:
            decompressed = zlib.decompress(raw)
        except Exception as e:
            compression = f"zlib_error:{e}"
    elif zstd_info is not None:
        compression = "zstd"

    text_out = _clean_text(decompressed, args.charset)
    pb_out = None if text_out is not None else _try_parse_protobuf(decompressed)
    thrift_out = None if (text_out is not None or pb_out is not None) else _try_parse_thrift_compact(decompressed)

    hints = []
    if zstd_info is not None:
        if zstd_info["hasDictionaryId"]:
            hints.append(
                f"Payload is Zstd compressed with dictionary id {zstd_info['dictIdHex']}. "
                "Inspect APK assets/resources or `libbdzstd.so` / `libsscronet.so` for matching TTNet dictionary."
            )
        else:
            hints.append("Payload is standard Zstd compressed (magic 28 b5 2f fd); stdlib zlib cannot decode Zstd, use zstd-jni or Python zstandard.")
    elif text_out is None and pb_out is None and thrift_out is None:
        ent = _shannon_entropy(decompressed)
        if ent >= 7.5:
            hints.append("High entropy (>=7.5 bits/byte) with no gzip/zlib/zstd magic: likely Brotli (`Content-Encoding: br`) or app-layer encryption/custom obfuscation.")

    out = {
        "rawSize": len(raw),
        "decodedSize": len(decompressed),
        "magicHex": raw[:24].hex(" "),
        "entropyBitsPerByte": _shannon_entropy(decompressed),
        "detectedCompression": compression,
        "zstdHeader": zstd_info,
        "textPreview": text_out[:2000] if text_out is not None else None,
        "protobufDecoded": pb_out,
        "thriftCompactDecoded": thrift_out,
        "hints": hints,
    }
    print(json.dumps(out, indent=2, ensure_ascii=False))
    return 0


def main(argv):
    p = argparse.ArgumentParser(prog="analyze_so.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    p_so = sub.add_parser("inspect", help="Inspect an ELF64 .so file for TLS/Cronet/QUIC offsets and signatures")
    p_so.add_argument("so_path")
    p_so.add_argument("--pkg")
    p_so.add_argument("--out-plugin-json")

    p_apk = sub.add_parser("inspect-apk", help="Extract & inspect network .so libraries from an APK/split-APK")
    p_apk.add_argument("apk_path")
    p_apk.add_argument("--abi", default="arm64-v8a")
    p_apk.add_argument("--lib-pattern")
    p_apk.add_argument("--extract-dir")
    p_apk.add_argument("--pkg")
    p_apk.add_argument("--out-plugin-json")

    p_proc = sub.add_parser("proc-summary", help="Summarize /proc/<pid>/maps & net/{tcp,tcp6,udp,udp6} snapshot text")
    p_proc.add_argument("snapshot_txt")

    p_re = sub.add_parser("analyze-readelf", help="Parse readelf -sW output into TLS offsets & plugin JSON")
    p_re.add_argument("readelf_txt")
    p_re.add_argument("--lib", default="libssl.so")
    p_re.add_argument("--pkg")
    p_re.add_argument("--out-plugin-json")

    p_dec = sub.add_parser("decode-body", help="Analyze raw/base64 response body bytes (gzip/zlib/zstd dict/protobuf/thrift)")
    p_dec.add_argument("body_file")
    p_dec.add_argument("--base64", action="store_true")
    p_dec.add_argument("--charset", default="utf-8")

    args = p.parse_args(argv)
    if args.cmd == "inspect":
        return cmd_inspect(args)
    if args.cmd == "inspect-apk":
        return cmd_inspect_apk(args)
    if args.cmd == "proc-summary":
        return cmd_proc_summary(args)
    if args.cmd == "analyze-readelf":
        return cmd_analyze_readelf(args)
    if args.cmd == "decode-body":
        return cmd_decode_body(args)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
