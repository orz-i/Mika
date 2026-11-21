import struct, sys

def read_elf(path):
    b = open(path, 'rb').read()
    phoff = struct.unpack_from('<Q', b, 32)[0]
    phentsize = struct.unpack_from('<H', b, 54)[0]
    phnum = struct.unpack_from('<H', b, 56)[0]
    segs = []
    for i in range(phnum):
        o = phoff + i * phentsize
        p_type = struct.unpack_from('<I', b, o)[0]
        p_flags = struct.unpack_from('<I', b, o + 4)[0]
        p_offset = struct.unpack_from('<Q', b, o + 8)[0]
        p_vaddr = struct.unpack_from('<Q', b, o + 16)[0]
        p_filesz = struct.unpack_from('<Q', b, o + 32)[0]
        segs.append((p_type, p_flags, p_offset, p_vaddr, p_filesz))
    return b, segs

PATTS = {
    'MOV W2,#226 (SSL_write ERR)': 0x52801c42,
    'MOV W2,#66  (SSL_peek)':      0x52800842,
    'MOV W0,#16':                  0x52800200,
    'MOV W2,#111':                 0x52800de2,
    'MOV W2,#215':                 0x52801ae2,
}

def main(path):
    b, segs = read_elf(path)
    text = [s for s in segs if s[0] == 1 and (s[1] & 1)]
    if not text:
        print('no exec segment'); return
    p_type, p_flags, off, va, sz = max(text, key=lambda s: s[4])
    print(f'.text offset=0x{off:x} vaddr=0x{va:x} size=0x{sz:x}')
    blk = b[off:off + sz]
    for name, val in PATTS.items():
        needle = struct.pack('<I', val)
        cnt = blk.count(needle)
        idx = blk.find(needle)
        print(f'{name:<28} count={cnt:<5} first_off=0x{idx:x}' if cnt else f'{name:<28} count=0')
    print('ssl/ssl_lib.cc present:', b.find(b'ssl/ssl_lib.cc') != -1)

if __name__ == '__main__':
    main(sys.argv[1])
