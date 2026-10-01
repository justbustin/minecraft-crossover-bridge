#!/usr/bin/env python3
"""Runs Minecraft commands in the running bridge world and prints their chat feedback.

Commands go through the dev-command file (/tmp/mhwmc/mc_cmd.txt, `run <command>`); their
feedback ("Test passed", "Changed the block at ...") is read back from latest.log.

    python3 monster-hunter-world/scripts/mccmd.py "setblock 16413 101 -4 minecraft:tnt" "summon minecraft:tnt 16413.5 102 -3.5 {fuse:30}"
    python3 monster-hunter-world/scripts/mccmd.py --surface 16413 -4   # top MHW-terrain voxel of a column and its HEIGHT (1-16)

Also importable: `sys.path.insert(0, "monster-hunter-world/scripts"); import mccmd; mccmd.run([...])`.
"""
import os
import re
import sys
import time

LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "mc-bridge", "run", "logs", "latest.log")
CMD = "/tmp/mhwmc/mc_cmd.txt"


def run(cmds, wait=2.5):
    """Runs the commands in order; returns the chat lines they produced (one per command, usually)."""
    size = os.path.getsize(LOG)
    with open(CMD + ".tmp", "w") as f:
        f.write("".join(f"run {c}\n" for c in cmds))
    os.replace(CMD + ".tmp", CMD)
    deadline = time.time() + wait + len(cmds) * 0.05
    out = []
    while time.time() < deadline:
        time.sleep(0.3)
        with open(LOG, "rb") as f:
            f.seek(size)
            new = f.read().decode(errors="replace")
        out = re.findall(r"\[CHAT\] (.*)", new)
        if len(out) >= len(cmds):
            break
    return out


def column_surface(x, z, ylo=90, yhi=115):
    """(y, height) of the top mhwbridge:terrain voxel in column (x, z), or None. The ground
    surface is at y + height/16."""
    ys = list(range(yhi, ylo - 1, -1))
    res = run([f"execute if block {x} {y} {z} mhwbridge:terrain" for y in ys])
    if len(res) < len(ys):
        return None
    top = next((y for y, r in zip(ys, res) if r.startswith("Test passed")), None)
    if top is None:
        return None
    hs = list(range(1, 17))
    res = run([f"execute if block {x} {top} {z} mhwbridge:terrain[height={h}]" for h in hs])
    return top, next((h for h, r in zip(hs, res) if r.startswith("Test passed")), None)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    if argv[1] == "--surface":
        print(column_surface(int(argv[2]), int(argv[3])))
    else:
        for line in run(argv[1:]):
            print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
