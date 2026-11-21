#!/usr/bin/env python3
"""For each call to SSL_get_error, find the immediately preceding bl -> that's
SSL_read / SSL_write (or SSL_peek / SSL_do_handshake)."""
import struct
import sys
import numpy as np

PATH = "libwebviewchromium.so"
GETERR = 0x31F44DC
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


def find_start(idx, max_back=8192):
    for j in range(idx, max(0, idx - max_back), -1):
        w = int(words[j])
        if w in (PACIASP, BTIC):
            return j
        if (w & 0xFF8003FF) == 0xD10003FF:
            return j
        if (w & 0xFFC00000) == 0xA9800000 and ((w >> 5) & 31) == 31 and (w & 31) == 29 and ((w >> 10) & 31) == 30:
            return j
    return None


def bl_target(i):
    w = int(words[i])
    if (w & 0xFC000000) != 0x94000000:
        return None
    imm = w & 0x03FFFFFF
    if imm & 0x02000000:
        imm -= 0x04000000
    return text_va + i * 4 + imm * 4


# all calls to SSL_get_error
gerr_calls = []
CH = 1 << 22
for base in range(0, n, CH):
    blk = words[base:base + CH]
    m = (blk & np.uint32(0xFC000000)) == np.uint32(0x94000000)
    for k in np.nonzero(m)[0]:
        i = base + int(k)
        if bl_target(i) == GETERR:
            gerr_calls.append(i)

print("SSL_get_error calls: %d" % len(gerr_calls))
print("\n%-12s %-12s %-34s" % ("call@", "prev_bl->", "caller func"))
seen = {}
for c in gerr_calls:
    prev = None
    for j in range(c - 1, max(0, c - 60), -1):
        t = bl_target(j)
        if t is not None:
            prev = (j, t)
            break
    fs = find_start(c)
    fva = text_va + fs * 4 if fs is not None else 0
    pva = prev[1] if prev else 0
    seen.setdefault((fva, pva), 0)
    seen[(fva, pva)] += 1
    print("0x%-10x 0x%-10x func=0x%-9x" % (text_va + c * 4, pva, fva))

print("\n== unique (caller, prev_callee) ==")
for (fva, pva), cnt in sorted(seen.items()):
    print("  caller=0x%-9x  calls=0x%-9x  x%d" % (fva, pva, cnt))
