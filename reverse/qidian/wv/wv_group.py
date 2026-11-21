#!/usr/bin/env python3
"""Group ssl_lib.cc error sites by owning function, to identify SSL_read/SSL_write."""
import struct
import sys
import numpy as np

PATH = sys.argv[1] if len(sys.argv) > 1 else "libwebviewchromium.so"
data = open(PATH, "rb").read()

e_phoff, = struct.unpack_from("<Q", data, 0x20)
e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
e_shoff, = struct.unpack_from("<Q", data, 0x28)
e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)

loads = []
for i in range(e_phnum):
    o = e_phoff + i * e_phentsize
    t, fl, off, va, pa, fsz, msz, al = struct.unpack_from("<IIQQQQQQ", data, o)
    if t == 1:
        loads.append((off, va, fsz))

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
    for o, va, fsz in loads:
        if o <= off < o + fsz:
            return va + (off - o)
    return None


hit = data.find(b"ssl/ssl_lib.cc")
start = hit
while start > 0 and data[start - 1] != 0:
    start -= 1
str_va = off_to_va(start)
print("ssl_lib.cc vaddr=0x%x" % str_va)

text = [s for s in secs if sname(s[0]) == ".text"][0]
text_off, text_va, text_sz = text[3], text[2], text[4]
words = np.frombuffer(data, dtype="<u4", count=text_sz // 4, offset=text_off).astype(np.uint32)
n = len(words)

# collect every error site: adrp/add x3 -> str_va, then mov w0,#16 within a few insns
sites = []
CH = 1 << 22
for base in range(0, n, CH):
    blk = words[base:base + CH]
    m = (blk & np.uint32(0x9F000000)) == np.uint32(0x90000000)
    for k in np.nonzero(m)[0]:
        i = base + int(k)
        w = int(words[i])
        rd = w & 31
        imm = (((w >> 5) & 0x7FFFF) << 2) | ((w >> 29) & 3)
        if imm & (1 << 20):
            imm -= 1 << 21
        page = ((text_va + i * 4) & ~0xFFF) + (imm << 12)
        if page != (str_va & ~0xFFF):
            continue
        for d in range(1, 5):
            if i + d >= n:
                break
            w2 = int(words[i + d])
            if (w2 & 0x7F800000) != 0x11000000 or (w2 & 31) != rd or ((w2 >> 5) & 31) != rd:
                continue
            imm12 = (w2 >> 10) & 0xFFF
            if (w2 >> 22) & 1:
                imm12 <<= 12
            if page + imm12 != str_va:
                continue
            # expect mov w0,#16 shortly after
            for e in range(d + 1, d + 5):
                if i + e >= n:
                    break
                if int(words[i + e]) == 0x52800200:
                    reason = 0
                    for f in range(e + 1, e + 5):
                        wf = int(words[i + f])
                        if (wf & 0xFFE0001F) == 0x52800002:  # mov w2,#imm
                            reason = (wf >> 5) & 0xFFFF
                            break
                    sites.append((i, reason))
                    break
            break

sites = sorted(set(sites))
print("error sites: %d" % len(sites))

PACIASP = 0xD503233F
BTIC = 0xD503245F


def find_func_start(idx, max_back=8192):
    lo = max(0, idx - max_back)
    for j in range(idx, lo, -1):
        w = int(words[j])
        if w in (PACIASP, BTIC):
            return j
        if (w & 0xFF8003FF) == 0xD10003FF:
            return j
        if (w & 0xFFC00000) == 0xA9800000 and ((w >> 5) & 31) == 31 and (w & 31) == 29 and ((w >> 10) & 31) == 30:
            return j
    return None


groups = {}
for i, reason in sites:
    fs = find_func_start(i)
    groups.setdefault(fs, []).append((i, reason))

print("\n== functions touching ssl_lib.cc errors ==")
rows = []
for fs, items in groups.items():
    va = text_va + fs * 4 if fs is not None else -1
    rows.append((va, fs, items))
rows.sort()
for va, fs, items in rows:
    reasons = [r for _, r in items]
    print("func 0x%-9x  sites=%d  reasons=%s" % (va, len(items), [hex(r) for r in reasons]))
    for i, r in items:
        print("      site 0x%x  reason=0x%x" % (text_va + i * 4, r))

print("\n== adjacency (candidate read/write pairs) ==")
for a, b in zip(rows, rows[1:]):
    gap = b[0] - a[0]
    if 0 < gap < 0x800:
        print("0x%x -> 0x%x  gap=0x%x  reasonsA=%s reasonsB=%s"
              % (a[0], b[0], gap, len(a[2]), len(b[2])))
