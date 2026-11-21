import struct, sys

def uleb(b, o):
    r = 0; s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7f) << s
        if not x & 0x80: return r, o
        s += 7

def dump(path):
    b = open(path, 'rb').read()
    n, off = struct.unpack_from('<II', b, 56)
    out = []
    for i in range(n):
        o = struct.unpack_from('<I', b, off + i * 4)[0]
        _, p = uleb(b, o)
        e = b.index(b'\0', p)
        try:
            out.append(b[p:e].decode('utf-8'))
        except UnicodeDecodeError:
            pass
    return out

if __name__ == '__main__':
    for s in dump(sys.argv[1]):
        print(s)
