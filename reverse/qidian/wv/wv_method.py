#!/usr/bin/env python3
"""Find functions that dispatch through method table offset 0x50/0x58 (ssl_write_app_data)."""
import struct
import sys
import numpy as np

PATH = "libwebviewchromium.so"
d = open(PATH, "rb").read()
e_shoff, = struct.unpack_from("<Q", d, 0x28)
e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", d, 0x3A)
secs = []
for i in range(e_shnum):
    o = e_shoff + i * e_shentsize
    nm, typ, fl, addr, off, sz, link, info, al, ent = struct.unpack_from("<IIQQQQIIQQ", d, o)
    secs.append((nm, typ, addr, off, sz))
shstr = secs[e_shstrndx]


def sname(nm):
    s = shstr[3] + nm
    e = d.index(b"\x00", s)
    return d[s:e].decode("latin1")


text = [s for s in secs if sname(s[0]) == ".text"][0]
text_off, text_va, text_sz = text[3], text[2], text[4]
words = np.frombuffer(d, dtype="<u4", count=text_sz // 4, offset=text_off).astype(np.uint32)
n = len(words)
PACIASP, BTIC = 0xD503233F, 0xD503245F


def find_start(idx, max_back=6000):
    for j in range(idx, max(0, idx - max_back), -1):
        w = int(words[j])
        if w in (PACIASP, BTIC):
            return j
        if (w & 0xFF8003FF) == 0xD10003FF:
            return j
        if (w & 0xFFC00000) == 0xA9800000 and ((w >> 5) & 31) == 31 and (w & 31) == 29 and ((w >> 10) & 31) == 30:
            return j
    return None


def scan(imm_bytes):
    """LDR xt,[xn,#imm] followed within 4 insns by BLR xt"""
    off = imm_bytes // 8
    res = []
    CH = 1 << 22
    for base in range(0, n, CH):
        blk = words[base:base + CH]
        m = ((blk & np.uint32(0xFFC00000)) == np.uint32(0xF9400000)) & \
            ((blk >> np.uint32(10)) & np.uint32(0xFFF) == np.uint32(off))
        for k in np.nonzero(m)[0]:
            i = base + int(k)
            rt = int(words[i]) & 31
            for d2 in range(1, 5):
                if i + d2 >= n:
                    break
                w2 = int(words[i + d2])
                if (w2 & 0xFFFFFC1F) == 0xD63F0000 and ((w2 >> 5) & 31) == rt:
                    res.append((i, i + d2))
                    break
    return res


for imm in (0x48, 0x50, 0x58):
    hits = scan(imm)
    print("\n=== method+0x%02x -> blr : %d hits ===" % (imm, len(hits)))
    seen = set()
    for i, b in hits:
        fs = find_start(i)
        fva = text_va + fs * 4 if fs is not None else 0
        if fva in seen:
            continue
        seen.add(fva)
        print("  func=0x%-9x  ldr@0x%x  blr@0x%x" % (fva, text_va + i * 4, text_va + b * 4))
