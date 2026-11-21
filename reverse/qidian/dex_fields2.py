import struct, sys, re

def uleb(b, o):
    r = 0; s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7f) << s
        if not x & 0x80: return r, o
        s += 7

def run(path, pats):
    b = open(path, 'rb').read()
    ss, so = struct.unpack_from('<II', b, 56)
    ts, to = struct.unpack_from('<II', b, 64)
    fs, fo = struct.unpack_from('<II', b, 80)
    cs, co = struct.unpack_from('<II', b, 96)

    def st(i):
        off = struct.unpack_from('<I', b, so + i * 4)[0]
        _, p = uleb(b, off)
        e = b.index(b'\0', p)
        return b[p:e].decode('utf8', 'replace')

    def tn(i):
        return st(struct.unpack_from('<I', b, to + i * 4)[0])

    fields = []
    for i in range(fs):
        c, t, n = struct.unpack_from('<HHI', b, fo + i * 8)
        fields.append((tn(c), st(n), tn(t)))

    rx = re.compile('|'.join(pats), re.I) if pats else None
    for i in range(cs):
        base = co + i * 32
        ci, = struct.unpack_from('<I', b, base)
        nm = tn(ci)
        if not rx or not rx.search(nm):
            continue
        cdo, = struct.unpack_from('<I', b, base + 24)
        if cdo == 0:
            print(f"### {nm}  (no class_data)")
            continue
        o = cdo
        sf, o = uleb(b, o)
        inf, o = uleb(b, o)
        dm, o = uleb(b, o)
        vm, o = uleb(b, o)
        for _ in range(sf):
            _, o = uleb(b, o); _, o = uleb(b, o)
        idx = 0
        print(f"### {nm}")
        for _ in range(inf):
            d, o = uleb(b, o); _, o = uleb(b, o)
            idx += d
            cls, fn, ft = fields[idx]
            print(f"    {ft:<32} {fn}")
        print()

if __name__ == '__main__':
    run(sys.argv[1], sys.argv[2:])
