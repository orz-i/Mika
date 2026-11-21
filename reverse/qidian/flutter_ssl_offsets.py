#!/usr/bin/env python3
"""定位 libflutter.so 中 BoringSSL 的 SSL_write / SSL_read / SSL_set_tlsext_host_name。

思路：libflutter 的 BoringSSL 已被 strip，但 ssl_lib.cc 的调试文件名字符串还在
（ERR_put_error 的第 4 个参数）。通过 ADRP+ADD 反查引用点，得到 ssl_lib.cc 内
所有函数，再按各自特征区分 read/write。
"""
import struct
import sys

PT_LOAD = 1
SHT_PROGBITS = 1
SHF_EXECINSTR = 0x4


def load(path):
    b = open(path, "rb").read()
    assert b[:4] == b"\x7fELF", "not elf"
    e_phoff, = struct.unpack_from("<Q", b, 32)
    e_shoff, = struct.unpack_from("<Q", b, 40)
    e_phentsize, e_phnum = struct.unpack_from("<HH", b, 54)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", b, 58)

    phdrs = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from(
            "<IIQQQQQQ", b, off)
        phdrs.append(dict(type=p_type, flags=p_flags, off=p_offset, va=p_vaddr,
                          filesz=p_filesz, memsz=p_memsz))

    shdrs = []
    shstr = b""
    if e_shoff:
        base = e_shoff + e_shstrndx * e_shentsize
        _, _, _, _, s_off, s_size = struct.unpack_from("<IIQQQQ", b, base)
        shstr = b[s_off:s_off + s_size]
        for i in range(e_shnum):
            o = e_shoff + i * e_shentsize
            name, typ, flags, addr, off, size = struct.unpack_from("<IIQQQQ", b, o)
            end = shstr.find(b"\0", name)
            nm = shstr[name:end].decode("utf8", "replace") if name < len(shstr) else ""
            shdrs.append(dict(name=nm, type=typ, flags=flags, addr=addr, off=off, size=size))
    return b, phdrs, shdrs


def sec(b, shdrs, name):
    for s in shdrs:
        if s["name"] == name:
            return s
    return None


def off_to_va(phdrs, off):
    for p in phdrs:
        if p["type"] == PT_LOAD and p["off"] <= off < p["off"] + p["filesz"]:
            return p["va"] + off - p["off"]
    return None


def va_to_off(phdrs, va):
    for p in phdrs:
        if p["type"] == PT_LOAD and p["va"] <= va < p["va"] + p["filesz"]:
            return p["off"] + va - p["va"]
    return None


def adrp_add_refs(words, text_va, target_va):
    """扫描 ADRP+ADD 组合，返回引用 target_va 的指令下标。"""
    hits = []
    n = len(words)
    for i in range(n - 1):
        w = words[i]
        if (w & 0x9F000000) != 0x90000000:  # ADRP
            continue
        rd = w & 0x1F
        immlo = (w >> 29) & 0x3
        immhi = (w >> 5) & 0x7FFFF
        imm = (immhi << 2) | immlo
        if imm & (1 << 20):
            imm -= 1 << 21
        pc = text_va + i * 4
        page = (pc & ~0xFFF) + (imm << 12)
        # 下一条应是 ADD Xd, Xn, #imm12
        w2 = words[i + 1]
        if (w2 & 0x7F800000) != 0x11000000:
            continue
        if (w2 & 0x1F) != rd or ((w2 >> 5) & 0x1F) != rd:
            continue
        imm12 = (w2 >> 10) & 0xFFF
        if (w2 >> 22) & 1:
            imm12 <<= 12
        if page + imm12 == target_va:
            hits.append(i)
    return hits


def func_start(words, idx, max_back=4096):
    for j in range(idx, max(idx - max_back, 0) - 1, -1):
        if words[j] in (0xD503233F, 0xD503245F):  # PACIASP / BTI C
            return j
    return None


def main(path):
    b, phdrs, shdrs = load(path)
    text = sec(b, shdrs, ".text")
    if text:
        text_off, text_va, text_sz = text["off"], text["addr"], text["size"]
    else:
        for p in phdrs:
            if p["type"] == PT_LOAD and (p["flags"] & 1):
                text_off, text_va, text_sz = p["off"], p["va"], p["filesz"]
                break
    words = list(struct.unpack_from("<%dI" % (text_sz // 4), b, text_off))
    print("text va=0x%x size=0x%x words=%d" % (text_va, text_sz, len(words)))

    for needle in (b"ssl/ssl_lib.cc", b"ssl/ssl_cipher.cc"):
        pos = b.find(needle + b"\0")
        if pos < 0:
            print("%s: NOT FOUND" % needle.decode())
            continue
        va = off_to_va(phdrs, pos)
        print("%s @ file 0x%x va 0x%x" % (needle.decode(), pos, va))
        refs = adrp_add_refs(words, text_va, va)
        funcs = sorted({func_start(words, r) for r in refs})
        print("  refs=%d funcs=%s" % (len(refs),
              ["0x%x" % (text_va + f * 4) if f is not None else "?" for f in funcs]))

    # 定位 MOV W2,#226 的位置
    print("--- MOV W2,#226 sites ---")
    for i, w in enumerate(words):
        if w == 0x52801C42:
            f = func_start(words, i)
            print("  idx=%d va=0x%x func=0x%x (+0x%x)" % (
                i, text_va + i * 4, text_va + f * 4 if f is not None else 0,
                (i - f) * 4 if f is not None else 0))


if __name__ == "__main__":
    main(sys.argv[1])
