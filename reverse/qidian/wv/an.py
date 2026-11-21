#!/usr/bin/env python3
import struct
import sys
from collections import Counter

HDR = struct.Struct("<IIIIIQ")
MAGIC = 0x45554141

path = sys.argv[1] if len(sys.argv) > 1 else "wv2.bin"
b = open(path, "rb").read()
off = 0
frames = []
while off + HDR.size <= len(b):
    magic, pid, uid, d, ln, ssl = HDR.unpack_from(b, off)
    if magic != MAGIC:
        break
    off += HDR.size
    p = b[off:off + ln]
    off += ln
    frames.append((d, ln, ssl, p))

chrom = [f for f in frames if 0x6A00000000 <= f[2] < 0x6B00000000]
app = [f for f in frames if 0xB400000000 <= f[2] < 0xB500000000]
print("frames=%d chromium=%d app=%d" % (len(frames), len(chrom), len(app)))
print("chromium ssl pointers:", [hex(s) for s in sorted(set(f[2] for f in chrom))][:12])

for needle in (b"argus/api", b"TaskList", b'"Result"', b"Process", b"HTTP/1.1 200"):
    print("  chromium contains %-14r : %d" % (needle, sum(1 for f in chrom if needle in f[3])))

print("\n-- chromium frames that mention argus/api --")
for d, ln, ssl, p in chrom:
    if b"argus/api" in p:
        print("  ssl=0x%x d=%d len=%d %r" % (ssl, d, ln, p[:140]))

print("\n-- per-ssl direction counts (chromium) --")
c = Counter((ssl, d) for _, _, ssl, _ in chrom)
for k, v in c.most_common(15):
    print("  ssl=0x%x d=%d n=%d" % (k[0], k[1], v))
