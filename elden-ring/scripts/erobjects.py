#!/usr/bin/env python3
"""Lists Elden Ring's map objects (assets) near the Tarnished, read live from CSWorldGeomMan.

    python3 elden-ring/scripts/erobjects.py                 # dynamic assets within 30 m
    python3 elden-ring/scripts/erobjects.py 60 --all        # static ones too, within 60 m
    python3 elden-ring/scripts/erobjects.py 40 --name AEG217

Each line: distance, MSB part name, class, position in the stable frame (block frame; open-world
tiles are shifted by 256 m per tile, like the bridge) and in Minecraft coordinates (from the saved
anchor), ready for `echo 'run tp @s X Y Z' > /tmp/ermc/mc_cmd.txt`.

Dynamic assets (CSWorldGeomDynamicIns) are the movable, breakable or usable ones (the chapel's
chairs are AEG217_025). "anim" marks those with an animation skeleton, where doors, levers and
lifts should be (inferred, not yet checked against a real door).
Read-only.
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from erctl import Bridge  # noqa: E402

WORLD_GEOM_MAN = 0x143D6DC18
WORLD_CHR_MAN = 0x143D69FF8
VT_STATIC = 0x142A898D0
VT_DYNAMIC = 0x142A87278
VT_ANIM_SKELETON = 0x142B6F1A0   # CSFD4LocationAnimSkeletonToMdlObjIdxModifier, at dynamic +0x4D8
ANCHOR_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "mc-bridge", "run", "saves",
                           "er-bridge", "erbridge-anchor.properties")


def wstr(b, addr):
    raw = b.try_read(addr, 128) if addr else None
    return raw.decode("utf-16-le", "replace").split("\0")[0] if raw else "?"


def blocks(b):
    """(block id, block data) for every loaded map block."""
    man = b.u64(WORLD_GEOM_MAN)
    head = b.u64(man + 0x20)
    out, stack, seen = [], [b.u64(head + 0x08)], set()
    while stack:
        n = stack.pop()
        if n in seen or n == head:
            continue
        seen.add(n)
        d = b.read(n, 0x30)
        if d[0x19]:  # nil node
            continue
        left, _, right = struct.unpack_from("<QQQ", d, 0)
        out.append((struct.unpack_from("<I", d, 0x20)[0], struct.unpack_from("<Q", d, 0x28)[0]))
        stack += [left, right]
    return out


def stable_offset(block):
    area = block >> 24
    if area in (60, 61):
        return 256.0 * ((block >> 16) & 0xFF), 256.0 * ((block >> 8) & 0xFF)
    return 0.0, 0.0


def load_anchor(zone):
    try:
        props = dict(line.strip().split("=", 1) for line in open(ANCHOR_FILE) if "=" in line and not line.startswith("#"))
        k = f"zone.{zone}."
        return (float(props[k + "ax"]), float(props[k + "ay"]), float(props[k + "az"]),
                int(props.get(k + "region", 0)), props.get(k + "flipZ", "true") == "true")
    except (OSError, KeyError, ValueError):
        return None


def main(argv):
    radius = float(argv[0]) if argv and not argv[0].startswith("-") else 30.0
    show_all = "--all" in argv
    name_filter = argv[argv.index("--name") + 1] if "--name" in argv else None
    b = Bridge()
    player = b.u64(b.u64(WORLD_CHR_MAN) + 0x1E508)
    pblock = b.u32(player + 0x6D0)
    bx, by, bz = b.floats(player + 0x6C0, 3)
    ox, oz = stable_offset(pblock)
    me = (bx + ox, by, bz + oz)
    open_world = (pblock >> 24) in (60, 61)
    zone = (pblock >> 24) << 24 if open_world else pblock
    anchor = load_anchor(zone)
    print(f"player block {pblock:#010x}, stable {me[0]:.2f} {me[1]:.2f} {me[2]:.2f}"
          + ("" if anchor else " (no Minecraft anchor saved for this zone)"))
    rows = []
    for block, data in blocks(b):
        # Legacy dungeons: only the player's own block shares its frame.
        if not open_world and block != pblock:
            continue
        # Open world: detailed 256 m tiles only (the last byte is the LOD: 1 and 2 are 512 m and
        # 1024 m tiles, whose offsets differ).
        if open_world and ((block >> 24) != (pblock >> 24) or (block & 0xFF) != 0):
            continue
        # A CSGrowableNodePool<CSWorldGeomIns*>: one contiguous array at +0xE8 of capacity + 2
        # slots, the capacity in the high half of +0xF8 (1024; 3072 in the Stranded Graveyard).
        # +0xF0 is its end only until it grows. Unused slots are null or free-list links, so every
        # slot is checked, and an object can be listed twice.
        hdr = b.read(data + 0xE8, 0x18)
        begin = struct.unpack_from("<Q", hdr, 0)[0]
        slots = struct.unpack_from("<I", hdr, 0x14)[0] + 2
        if not begin or slots > 200000:
            continue
        geoms = list(dict.fromkeys(g for g in struct.unpack(f"<{slots}Q", b.read(begin, 8 * slots)) if 0x10000 < g < 0x7FFFFFFFFFFF and g % 8 == 0))
        heads = b.read_many(geoms, 0x50)
        tox, toz = stable_offset(block)
        for g, h in zip(geoms, heads):
            if not h:
                continue
            vt = struct.unpack_from("<Q", h, 0)[0]
            if vt not in (VT_STATIC, VT_DYNAMIC) or (vt == VT_STATIC and not show_all):
                continue
            part = b.try_read(struct.unpack_from("<Q", h, 0x48)[0], 0x38)
            if not part:
                continue
            x, y, z = struct.unpack_from("<3f", part, 0x20)
            pos = (x + tox, y, z + toz)
            dist = sum((pos[i] - me[i]) ** 2 for i in range(3)) ** 0.5
            if dist > radius:
                continue
            name = wstr(b, struct.unpack_from("<Q", part, 0)[0])
            if name_filter and name_filter not in name:
                continue
            kind = "static"
            if vt == VT_DYNAMIC:
                kind = "dynamic"
                sk = b.try_read(g + 0x4D8, 8)
                ptr = struct.unpack_from("<Q", sk)[0] if sk else 0
                head = b.try_read(ptr, 8) if ptr else None
                if head and struct.unpack_from("<Q", head)[0] == VT_ANIM_SKELETON:
                    kind = "dynamic anim"
            roty = struct.unpack_from("<f", part, 0x30)[0]
            rows.append((dist, name, kind, pos, roty))
    rows.sort()
    for dist, name, kind, pos, roty in rows:
        mc = ""
        if anchor:
            ax, ay, az, region, flip = anchor
            dz = pos[2] - az
            mc = "  mc %8.2f %7.2f %8.2f" % (pos[0] - ax + 0.5 + region * 32768.0, pos[1] - ay + 100.0,
                                              (-dz if flip else dz) + 0.5)
        print("%6.1f m  %-18s %-12s stable %8.2f %7.2f %8.2f  roty %5.2f%s" % (dist, name, kind, *pos, roty, mc))
    print(f"{len(rows)} object(s) within {radius:g} m")


if __name__ == "__main__":
    main(sys.argv[1:])
