#!/usr/bin/env python3
"""Dev: push a Minecraft-style hit into the er-bridge damage queue, as the mod does.

    python3 elden-ring/scripts/erhit.py <entity handle hex> <minecraft damage> [crit]

The DLL applies it on the game thread (game.cpp service_damage/apply_hit) and logs
"combat: ..." lines to /tmp/ermc/er-bridge.log.
"""
import mmap, os, struct, sys
OFF = 0x280000
RING = 256

def push(handle, amount, flags=0, pos=(0.0, 0.0, 0.0)):
    fd = os.open("/tmp/ermc/bridge.shm", os.O_RDWR)
    mm = mmap.mmap(fd, 8 * 1024 * 1024, mmap.MAP_SHARED, mmap.PROT_READ | mmap.PROT_WRITE)
    w = struct.unpack_from("<I", mm, OFF)[0]
    struct.pack_into("<Qf3fII", mm, OFF + 0x10 + (w % RING) * 0x20, handle, amount, *pos, flags, 0)
    struct.pack_into("<I", mm, OFF, (w + 1) & 0xFFFFFFFF)

if __name__ == "__main__":
    push(int(sys.argv[1], 16), float(sys.argv[2]), 1 if len(sys.argv) > 3 and sys.argv[3] == "crit" else 0)
