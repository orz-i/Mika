#!/usr/bin/env python3
"""汇总 atlas-device 下各轮评测的步数/耗时/通过，按开关注分组打均值。"""
import glob
import json
import os
import statistics
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(os.path.dirname(ROOT), "output", "atlas-device")


def load(run_dir):
    scores = os.path.join(run_dir, "scores.jsonl")
    if not os.path.exists(scores):
        return None
    with open(scores, encoding="utf-8") as f:
        for line in f:
            d = json.loads(line)
            events = glob.glob(os.path.join(run_dir, "*", "run", "events.jsonl"))
            hints = 0
            for e in events:
                with open(e, encoding="utf-8") as ef:
                    hints = sum(1 for line2 in ef if '"atlas_hint"' in line2)
            return {
                "id": d["id"],
                "steps": d["steps"],
                "wall": round(d["wallMs"] / 1000, 1),
                "passed": d["passed"],
                "outcome": d["outcome"],
                "hints": hints,
            }
    return None


def main():
    groups = {}
    for d in sorted(glob.glob(os.path.join(BASE, "*"))):
        if not os.path.isdir(d):
            continue
        row = load(d)
        if not row:
            continue
        name = os.path.basename(d)
        if "-on-" in name:
            groups.setdefault("hints_on", []).append((name, row))
        elif "-off-" in name:
            groups.setdefault("hints_off", []).append((name, row))
        else:
            groups.setdefault("other", []).append((name, row))

    for g in ("hints_on", "hints_off", "other"):
        rows = groups.get(g, [])
        if not rows:
            continue
        print(f"== {g}")
        for name, r in rows:
            print(f"  {name:16} steps={r['steps']:2}  {r['wall']:6}s  passed={r['passed']}  hints={r['hints']}")
        for key in ("steps", "wall"):
            vals = [r[key] for _, r in rows]
            print(f"  {key}: mean={statistics.mean(vals):.1f} median={statistics.median(vals):.1f} "
                  f"min={min(vals)} max={max(vals)}")
        print(f"  passed: {sum(1 for _, r in rows if r['passed'])}/{len(rows)}")
        print()


if __name__ == "__main__":
    sys.exit(main())
