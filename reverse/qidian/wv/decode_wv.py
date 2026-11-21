#!/usr/bin/env python3
"""Reassemble chunked+gzip HTTP responses captured from chromium SSL_read."""
import gzip
import struct
import sys

HDR = struct.Struct("<IIIIIQ")
MAGIC = 0x45554141


def load(path):
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
    return frames


def parse_stream(buf):
    """Walk HTTP/1.1 responses, de-chunk and gunzip."""
    out = []
    i = 0
    while True:
        j = buf.find(b"HTTP/1.1 ", i)
        if j < 0:
            break
        k = buf.find(b"\r\n\r\n", j)
        if k < 0:
            break
        head = buf[j:k].decode("latin1", "replace")
        lines = head.split("\r\n")
        hdrs = {}
        for line in lines[1:]:
            if ":" in line:
                a, v = line.split(":", 1)
                hdrs[a.strip().lower()] = v.strip()
        status = lines[0]
        p = k + 4
        if hdrs.get("transfer-encoding", "").lower() == "chunked":
            body = b""
            ok = True
            while True:
                e = buf.find(b"\r\n", p)
                if e < 0 or e - p > 16:
                    ok = False
                    break
                try:
                    sz = int(buf[p:e], 16)
                except ValueError:
                    ok = False
                    break
                p = e + 2
                if sz == 0:
                    break
                body += buf[p:p + sz]
                p += sz + 2
            if not ok:
                i = j + 1
                continue
            i = p
        else:
            try:
                cl = int(hdrs.get("content-length", "0"))
            except ValueError:
                cl = 0
            body = buf[p:p + cl]
            i = p + cl
        enc = hdrs.get("content-encoding", "")
        if "gzip" in enc and body[:2] == b"\x1f\x8b":
            try:
                body = gzip.decompress(body)
            except Exception:
                pass
        out.append((status, enc, len(body), body))
    return out


frames = load(sys.argv[1] if len(sys.argv) > 1 else "wv2.bin")
by_ssl = {}
for d, ln, ssl, p in frames:
    if d == 2 and 0x6A00000000 <= ssl < 0x6B00000000:
        by_ssl.setdefault(ssl, bytearray()).extend(p)

print("chromium read connections: %d\n" % len(by_ssl))
allout = []
for ssl, buf in by_ssl.items():
    res = parse_stream(bytes(buf))
    for status, enc, n, body in res:
        allout.append((ssl, status, enc, n, body))

print("decoded responses: %d\n" % len(allout))
with open("decoded.txt", "wb") as f:
    for ssl, status, enc, n, body in allout:
        f.write(b"\n===== ssl=0x%x %s enc=%s len=%d =====\n" % (ssl, status.encode(), enc.encode(), n))
        f.write(body[:20000])
        f.write(b"\n")
        head = body[:90].decode("utf8", "replace").replace("\n", " ")
        print("ssl=0x%-12x %-24s enc=%-5s len=%-7d %s" % (ssl, status, enc, n, head))
