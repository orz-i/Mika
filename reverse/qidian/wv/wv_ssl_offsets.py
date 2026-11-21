#!/usr/bin/env python3
"""Scan libwebviewchromium.so for BoringSSL thunk / SSL_read|write candidates.

Mirrors aau_ebpf_loader.c's ADRP+ADD thunk heuristic, but dumps every hit so
the pattern can be eyeballed instead of guessed.
"""
import struct
import sys
import numpy as np

PATH = sys.argv[1] if len(sys.argv) > 1 else "libwebviewchromium.so"

data = open(PATH, "rb").read()
print("file size 0x%x" % len(data))

e_phoff, = struct.unpack_from("<Q", data, 0x20)
e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
e_shoff, = struct.unpack_from("<Q", data, 0x28)
e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)

phdrs = []
for i in range(e_phnum):
    o = e_phoff + i * e_phentsize
    p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from(
        "<IIQQQQQQ", data, o)
    phdrs.append((p_type, p_flags, p_offset, p_vaddr, p_filesz, p_memsz, p_align))

print("\n== PT_LOAD ==")
loads = [p for p in phdrs if p[0] == 1]
for p in loads:
    print("  flags=%d off=0x%-9x vaddr=0x%-9x filesz=0x%-9x memsz=0x%-9x align=0x%x"
          % (p[1], p[2], p[3], p[4], p[5], p[6]))

secs = []
for i in range(e_shnum):
    o = e_shoff + i * e_shentsize
    nm, typ, fl, addr, off, sz, link, info, al, ent = struct.unpack_from("<IIQQQQIIQQ", data, o)
    secs.append((nm, typ, addr, off, sz, link, ent))
shstr = secs[e_shstrndx]


def sname(nm):
    s = shstr[3] + nm
    e = data.index(b"\x00", s)
    return data[s:e].decode("latin1")


def off_to_va(off):
    for p in loads:
        if p[2] <= off < p[2] + p[4]:
            return p[3] + (off - p[2])
    return None


# ---- locate the ssl_lib.cc string and resolve its vaddr ----
hit = data.find(b"ssl/ssl_lib.cc")
if hit < 0:
    print("ssl/ssl_lib.cc NOT FOUND")
    sys.exit(1)
# walk back to string start
start = hit
while start > 0 and data[start - 1] != 0:
    start -= 1
str_va = off_to_va(start)
print("\nssl_lib.cc str: file_off=0x%x vaddr=0x%x" % (start, str_va))

text = [s for s in secs if sname(s[0]) == ".text"][0]
text_off, text_va, text_sz = text[3], text[2], text[4]
print(".text off=0x%x va=0x%x size=0x%x words=%d" % (text_off, text_va, text_sz, text_sz // 4))

words = np.frombuffer(data, dtype="<u4", count=text_sz // 4, offset=text_off).astype(np.uint32)
n = len(words)

# ---- pass 1: find every ADRP whose page matches the string page ----
CH = 1 << 22
hits = []
for base in range(0, n, CH):
    blk = words[base:base + CH]
    m = (blk & np.uint32(0x9F000000)) == np.uint32(0x90000000)
    idx = np.nonzero(m)[0]
    for k in idx:
        i = base + int(k)
        w = int(words[i])
        rd = w & 31
        imm = (((w >> 5) & 0x7FFFF) << 2) | ((w >> 29) & 3)
        if imm & (1 << 20):
            imm -= 1 << 21
        pc = text_va + i * 4
        page = (pc & ~0xFFF) + (imm << 12)
        if page != (str_va & ~0xFFF):
            continue
        # look ahead up to 4 insns for the matching ADD
        for d in range(1, 5):
            if i + d >= n:
                break
            w2 = int(words[i + d])
            if (w2 & 0x7F800000) != 0x11000000:
                continue
            if (w2 & 31) != rd or ((w2 >> 5) & 31) != rd:
                continue
            imm12 = (w2 >> 10) & 0xFFF
            if (w2 >> 22) & 1:
                imm12 <<= 12
            if page + imm12 == str_va:
                hits.append(i)
            break

print("\nADRP+ADD refs to ssl_lib.cc: %d" % len(hits))
for i in hits:
    print("  idx=%d va=0x%x off=0x%x" % (i, text_va + i * 4, text_off + i * 4))
    ctx = [int(x) for x in words[max(0, i - 3):i + 8]]
    print("    ctx:", " ".join("%08x" % x for x in ctx))
