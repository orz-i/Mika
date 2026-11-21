#!/usr/bin/env python3
"""Cross-reference: which BoringSSL funcs (ssl_lib.cc error sites) are called
from Chromium net layer (ssl_client_socket_impl.cc). Those are SSL_read/write."""
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
    secs.append((nm, typ, addr, off, sz))
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


def str_start(needle):
    h = data.find(needle)
    if h < 0:
        return None
    s = h
    while s > 0 and data[s - 1] != 0:
        s -= 1
    return s


text = [s for s in secs if sname(s[0]) == ".text"][0]
text_off, text_va, text_sz = text[3], text[2], text[4]
words = np.frombuffer(data, dtype="<u4", count=text_sz // 4, offset=text_off).astype(np.uint32)
n = len(words)

PACIASP, BTIC = 0xD503233F, 0xD503245F


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


def all_refs(str_va):
    out = []
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
                if page + imm12 == str_va:
                    out.append(i)
                break
    return out


# --- SSL error sites (set A) ---
ssl_str = str_start(b"ssl/ssl_lib.cc")
ssl_va = off_to_va(ssl_str)
sites = []
for i in all_refs(ssl_va):
    for e in range(0, 6):
        if i + e < n and int(words[i + e]) == 0x52800200:
            sites.append(i)
            break

fs_of_site = {}
for i in sites:
    fs = find_func_start(i)
    fs_of_site.setdefault(fs, []).append(i)
A = {text_va + fs * 4: fs for fs in fs_of_site if fs is not None}

# --- Chromium net layer refs (set B) ---
net_str = str_start(b"net/socket/ssl_client_socket_impl.cc")
net_va = off_to_va(net_str)
print("ssl_client_socket_impl.cc vaddr=0x%x refs=%d" % (net_va, len(all_refs(net_va))))
B = {}
for i in all_refs(net_va):
    fs = find_func_start(i)
    if fs is not None:
        B.setdefault(text_va + fs * 4, fs)

print("\n== net-layer functions referencing ssl_client_socket_impl.cc: %d ==" % len(B))
for va in sorted(B):
    print("  func 0x%x" % va)

# --- collect bl targets from B functions ---
print("\n== bl targets from net-layer funcs that hit ssl_lib.cc error funcs ==")
found = {}
for va, fs in sorted(B.items()):
    lo = fs
    hi = min(n, fs + 20000)
    for j in range(lo, hi):
        w = int(words[j])
        if (w & 0xFC000000) != 0x94000000:
            continue
        imm = w & 0x03FFFFFF
        if imm & 0x02000000:
            imm -= 0x04000000
        tgt = text_va + j * 4 + imm * 4
        if tgt in A:
            found.setdefault(tgt, []).append(text_va + j * 4)

print("\n== RESULT: SSL_* candidates called from net layer ==")
for tgt in sorted(found):
    fs = A[tgt]
    reasons = fs_of_site.get((fs - text_va) // 4, [])
    print("  SSL func 0x%-9x  called_from=%s  error_sites=%d"
          % (tgt, [hex(x) for x in found[tgt][:6]], len(reasons)))

print("\n== file offsets in APK (add 0xddc000) ==")
for tgt in sorted(found):
    print("  va 0x%-9x  apk_off 0x%x" % (tgt, 0xDDC000 + tgt))
