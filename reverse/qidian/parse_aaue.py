#!/usr/bin/env python3
"""解析 aau_ebpf 的 AAUE 帧流：magic/pid/uid/direction/length/ssl_ptr + payload"""
import struct
import sys

HDR = struct.Struct("<IIIIIQ")  # 28 bytes
MAGIC = 0x45554141


def main(path, show=8):
    b = open(path, "rb").read()
    off = 0
    n = 0
    dirs = {}
    frames = []
    while off + HDR.size <= len(b):
        magic, pid, uid, direction, length, ssl_ptr = HDR.unpack_from(b, off)
        if magic != MAGIC:
            print("magic mismatch at 0x%x: 0x%x" % (off, magic))
            break
        off += HDR.size
        payload = b[off:off + length]
        off += length
        n += 1
        dirs[direction] = dirs.get(direction, 0) + 1
        frames.append((direction, ssl_ptr, payload))
    print("frames=%d dirs=%s" % (n, dirs))
    print("-" * 70)
    for direction, ssl_ptr, payload in frames[:show]:
        tag = {1: "WRITE", 2: "READ", 9: "JNI"}.get(direction, str(direction))
        head = payload[:160]
        try:
            text = head.decode("utf8", "replace")
        except Exception:
            text = repr(head)
        print("== %s len=%d ssl=0x%x" % (tag, len(payload), ssl_ptr))
        print("   " + text.replace("\r", "\\r").replace("\n", "\\n")[:300])
    return frames


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 8)
