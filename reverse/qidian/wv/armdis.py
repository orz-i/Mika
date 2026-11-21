#!/usr/bin/env python3
import sys
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

PATH = "libwebviewchromium.so"
START = int(sys.argv[1], 16)
END = int(sys.argv[2], 16)
d = open(PATH, "rb").read()
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
md.detail = False
for ins in md.disasm(d[START:END], START):
    print("0x%08x  %-8s %s" % (ins.address, ins.mnemonic, ins.op_str))
