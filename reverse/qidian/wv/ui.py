#!/usr/bin/env python3
"""Dump UI hierarchy on device, locate node by text, print tap coordinates."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

KEYWORDS = sys.argv[1:] or ["福利"]


def sh(cmd):
    return subprocess.run(["adb", "shell", cmd], capture_output=True, text=True).stdout


sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
xml = sh("cat /sdcard/ui.xml")
try:
    root = ET.fromstring(xml)
except ET.ParseError:
    print("parse failed, head:", xml[:200])
    sys.exit(1)

found = []
for n in root.iter("node"):
    t = (n.get("text") or "")
    d = (n.get("content-desc") or "")
    for kw in KEYWORDS:
        if t == kw or d == kw or kw in t or kw in d:
            b = n.get("bounds", "")
            m = re.findall(r"\d+", b)
            if len(m) == 4:
                x = (int(m[0]) + int(m[2])) // 2
                y = (int(m[1]) + int(m[3])) // 2
                found.append((kw, t or d, n.get("class"), x, y))
            break

if not found:
    print("no match for", KEYWORDS)
    texts = [(n.get("text"), n.get("bounds")) for n in root.iter("node") if n.get("text")]
    print("visible:", [t for t, _ in texts][:30])
else:
    for kw, t, c, x, y in found:
        print("MATCH kw=%s text=%r class=%s tap=(%d,%d)" % (kw, t, c, x, y))
