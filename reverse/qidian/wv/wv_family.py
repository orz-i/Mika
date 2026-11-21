#!/usr/bin/env python3
"""Find SSL_read/SSL_write family: functions whose prologue calls
0x31e0410 then 0x31e0bb8 (rwstate reset + handshake flush)."""
import struct
import sys
import numpy as np

PATH = "libwebviewchromium.so"
P1, P2 = 0x31E0410, 0x31E0BB8
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


def bl_t(i):
    w = int(words[i])
    if (w & 0xFC000000) != 0x94000000:
        return None
    imm = w & 0x03FFFFFF
    if imm & 0x02000000:
        imm -= 0x04000000
    return text_va + i * 4 + imm * 4


hits = []
for i in range(n - 1):
    if bl_t(i) == P1 and bl_t(i + 1) == P2:
        hits.append(i)

print("prologue fingerprint hits: %d" % len(hits))
for i in hits:
    print("  bl@0x%x  (func start ~0x%x)" % (text_va + i * 4, text_va + i * 4 - 8))
