import sys, struct

def uleb128(b, o):
    r = 0; s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7f) << s
        if not (x & 0x80): return r, o
        s += 7

def parse(path, targets):
    b = open(path, 'rb').read()
    string_ids_size, string_ids_off = struct.unpack_from('<II', b, 56)
    type_ids_size, type_ids_off = struct.unpack_from('<II', b, 64)
    field_ids_size, field_ids_off = struct.unpack_from('<II', b, 80)
    class_defs_size, class_defs_off = struct.unpack_from('<II', b, 96)

    def s(idx):
        off = struct.unpack_from('<I', b, string_ids_off + idx*4)[0]
        _, p = uleb128(b, off)
        e = b.index(b'\x00', p)
        return b[p:e].decode('utf-8', 'replace')

    def type_name(idx):
        return s(struct.unpack_from('<I', b, type_ids_off + idx*4)[0])

    fields = []
    for i in range(field_ids_size):
        c, t, n = struct.unpack_from('<HHI', b, field_ids_off + i*8)
        fields.append((type_name(c), s(n), type_name(t)))

    out = {}
    for i in range(class_defs_size):
        base = class_defs_off + i*32
        class_idx, _, _, _, _, _, class_data_off, _ = struct.unpack_from('<IIIIIIII', b, base)
        name = type_name(class_idx)
        short = name.strip('L;').replace('/', '.')
        if not any(t.lower() in short.lower() for t in targets):
            continue
        if class_data_off == 0: continue
        o = class_data_off
        sf, o = uleb128(b, o); inf, o = uleb128(b, o)
        dm, o = uleb128(b, o); vm, o = uleb128(b, o)
        o_before = o
        for _ in range(sf):
            _, o = uleb128(b, o); _, o = uleb128(b, o)
        fidx = 0
        fl = []
        for _ in range(inf):
            d, o = uleb128(b, o); _, o = uleb128(b, o)
            fidx += d
            cls, fname, ftype = fields[fidx]
            fl.append(f"{ftype} {fname}")
        # skip methods
        out[short] = fl

    for k in sorted(out):
        print(f"### {k}")
        for f in out[k]:
            print("   ", f)

if __name__ == '__main__':
    parse(sys.argv[1], sys.argv[2:])
