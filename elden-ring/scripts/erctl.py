#!/usr/bin/env python3
"""macOS-side client for the er-bridge debug mailbox (see elden-ring/er-bridge/include/bridge_protocol.h).

Lets us inspect and patch Elden Ring's memory live from macOS while the game runs in CrossOver.

    python3 elden-ring/scripts/erctl.py ping
    python3 elden-ring/scripts/erctl.py state
    python3 elden-ring/scripts/erctl.py read 0x145073e80 64
    python3 elden-ring/scripts/erctl.py floats 0x14500000 16
    python3 elden-ring/scripts/erctl.py sig "48 8B 0D ?? ?? ?? ?? E8"
    python3 elden-ring/scripts/erctl.py modules
    python3 elden-ring/scripts/erctl.py ground                   # raycast down through the player
    python3 elden-ring/scripts/erctl.py raycast x0 y0 z0 x1 y1 z1 [a b c]   # optional collision filter
    python3 elden-ring/scripts/erctl.py control                  # Minecraft's control block + game state, live
    python3 elden-ring/scripts/erctl.py fps                      # Elden Ring and Minecraft frame rates (heartbeats)
    python3 elden-ring/scripts/erctl.py shell i x0 y0 z0 x1 y1 z1  # player attack i (not implemented in the Elden Ring bridge)

Same protocol as the MHW bridge's scripts/mhwctl.py.

Also importable: `sys.path.insert(0, "elden-ring/scripts"); from erctl import Bridge`.
"""
import mmap
import os
import struct
import sys
import time

SHM_PATH = "/tmp/ermc/bridge.shm"
SHM_SIZE = 8 * 1024 * 1024
MAGIC = 0x434D484D
OFF_STATE = 0x100
OFF_CONTROL = 0x800
OFF_CMD = 0x1000
OFF_CMD_RESP = 0x2000
CMD_RESP_MAX = 0x100000 - OFF_CMD_RESP

CMD_PING, CMD_READ, CMD_WRITE, CMD_READ_MANY, CMD_SCAN, CMD_SCAN_FLOAT, CMD_QUERY, CMD_MODULES, CMD_RAYCAST, CMD_FIRE_SHELL = range(1, 11)
SCAN_IMAGE, SCAN_PRIVATE, SCAN_MAPPED, SCAN_EXEC_ONLY = 1, 2, 4, 8


class BridgeError(Exception):
    pass


class Bridge:
    def __init__(self, path=SHM_PATH):
        if not os.path.exists(path):
            raise BridgeError(f"{path} does not exist (is Elden Ring running via launch_er.sh?)")
        self.fd = os.open(path, os.O_RDWR)
        if os.path.getsize(path) < SHM_SIZE:
            raise BridgeError("shm file too small; bridge not initialised yet")
        self.mm = mmap.mmap(self.fd, SHM_SIZE, mmap.MAP_SHARED, mmap.PROT_READ | mmap.PROT_WRITE)
        magic, = struct.unpack_from("<I", self.mm, 0)
        if magic != MAGIC:
            raise BridgeError(f"bad magic {magic:#x}")

    # -- mailbox -------------------------------------------------------------------------
    def call(self, cmd, args=b"", timeout=15.0):
        # One request at a time across every process using the mailbox (several tools may
        # query the game at once; interleaved requests would corrupt each other's arguments).
        import fcntl
        with open("/tmp/ermc/erctl.lock", "w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            return self._call(cmd, args, timeout)

    def _call(self, cmd, args=b"", timeout=15.0):
        mm = self.mm
        req, resp = struct.unpack_from("<II", mm, OFF_CMD)
        if req != resp:
            # A previous client died mid-request; wait briefly for the DLL to catch up.
            t0 = time.time()
            while struct.unpack_from("<I", mm, OFF_CMD + 4)[0] != req and time.time() - t0 < 2:
                time.sleep(0.001)
        struct.pack_into("<II", mm, OFF_CMD + 8, cmd, len(args))
        mm[OFF_CMD + 0x18: OFF_CMD + 0x18 + len(args)] = args
        new = (req + 1) & 0xFFFFFFFF
        struct.pack_into("<I", mm, OFF_CMD, new)
        t0 = time.time()
        while struct.unpack_from("<I", mm, OFF_CMD + 4)[0] != new:
            if time.time() - t0 > timeout:
                raise BridgeError("timeout waiting for er-bridge (game frozen or bridge not running?)")
            time.sleep(0.0005)
        status, rlen = struct.unpack_from("<iI", mm, OFF_CMD + 0x10)
        data = bytes(mm[OFF_CMD_RESP: OFF_CMD_RESP + rlen])
        return status, data

    def ping(self):
        st, d = self.call(CMD_PING)
        return d.decode(errors="replace")

    def read(self, addr, n):
        out = bytearray()
        while n > 0:
            chunk = min(n, CMD_RESP_MAX)
            st, d = self.call(CMD_READ, struct.pack("<QI", addr, chunk))
            if st != 0:
                raise BridgeError(f"unreadable memory at {addr:#x}")
            out += d
            addr += chunk
            n -= chunk
        return bytes(out)

    def try_read(self, addr, n):
        try:
            return self.read(addr, n)
        except BridgeError:
            return None

    def write(self, addr, data):
        st, _ = self.call(CMD_WRITE, struct.pack("<QI", addr, len(data)) + data)
        if st != 0:
            raise BridgeError(f"write failed at {addr:#x}")

    def read_many(self, addrs, n):
        res = []
        maxc = min((0x1000 - 0x18 - 8) // 8, CMD_RESP_MAX // (n + 1))
        for i in range(0, len(addrs), maxc):
            part = addrs[i:i + maxc]
            st, d = self.call(CMD_READ_MANY, struct.pack("<II", len(part), n) + struct.pack(f"<{len(part)}Q", *part))
            if st != 0:
                raise BridgeError("read_many failed")
            ok = d[:len(part)]
            body = d[len(part):]
            for j in range(len(part)):
                res.append(body[j * n:(j + 1) * n] if ok[j] else None)
        return res

    def scan(self, pattern, mask=None, start=0, end=0x7FFFFFFFFFFF, flags=SCAN_IMAGE | SCAN_EXEC_ONLY, max_results=4096):
        if mask is None:
            mask = b"\xff" * len(pattern)
        st, d = self.call(CMD_SCAN, struct.pack("<QQIII", start, end, len(pattern), max_results, flags) + pattern + mask, timeout=120)
        if st != 0:
            raise BridgeError(f"scan failed {st}")
        return list(struct.unpack(f"<{len(d)//8}Q", d))

    def sig(self, text, **kw):
        pat, mask = bytearray(), bytearray()
        for tok in text.split():
            if tok.startswith("?"):
                pat.append(0); mask.append(0)
            else:
                pat.append(int(tok, 16)); mask.append(0xFF)
        return self.scan(bytes(pat), bytes(mask), **kw)

    def scan_float(self, lo, hi, start=0, end=0x7FFFFFFFFFFF, align=4, flags=SCAN_PRIVATE, max_results=100000):
        st, d = self.call(CMD_SCAN_FLOAT, struct.pack("<QQffIII", start, end, lo, hi, align, max_results, flags), timeout=300)
        if st != 0:
            raise BridgeError(f"scan_float failed {st}")
        return list(struct.unpack(f"<{len(d)//8}Q", d))

    def query(self, addr):
        st, d = self.call(CMD_QUERY, struct.pack("<Q", addr))
        if st != 0:
            return None
        base, alloc, size = struct.unpack_from("<QQQ", d, 0)
        state, prot, typ = struct.unpack_from("<III", d, 24)
        return dict(base=base, alloc=alloc, size=size, state=state, protect=prot, type=typ)

    def modules(self):
        st, d = self.call(CMD_MODULES)
        mods, o = [], 0
        while o < len(d):
            base, size, nl = struct.unpack_from("<QIH", d, o)
            name = d[o + 14:o + 14 + nl].decode(errors="replace")
            mods.append((name, base, size))
            o += 14 + nl
        return mods

    def raycast(self, start, end, camera_filter=False, filt=None):
        """Segment query against the game's collision, run on the game thread. Returns
        (hit, pos, normal, attr) or None if the game thread isn't ticking. `filt` = (a, b, c)
        Param::setAttr arguments for a custom collision filter."""
        flags = (1 if camera_filter else 0) | (2 if filt else 0)
        fa, fb, fc = filt or (0, 0, 0)
        st, d = self.call(CMD_RAYCAST, struct.pack("<6f4I", *start, *end, flags, fa, fb, fc))
        if st != 0:
            return None
        pos = struct.unpack_from("<3f", d, 0)
        normal = struct.unpack_from("<3f", d, 12)
        hit, attr = struct.unpack_from("<II", d, 24)
        return hit, pos, normal, attr

    def fire_shell(self, index, origin, target):
        """Game attack spawn (MHW: slinger shell `index`). Not implemented in er-bridge yet:
        returns None until the Elden Ring side supports it."""
        st, d = self.call(CMD_FIRE_SHELL, struct.pack("<I6f", index, *origin, *target))
        if st != 0:
            return None
        return struct.unpack_from("<Q", d, 0)[0]

    # -- typed helpers -------------------------------------------------------------------
    def u64(self, a): return struct.unpack("<Q", self.read(a, 8))[0]
    def u32(self, a): return struct.unpack("<I", self.read(a, 4))[0]
    def f32(self, a): return struct.unpack("<f", self.read(a, 4))[0]
    def floats(self, a, n): return struct.unpack(f"<{n}f", self.read(a, 4 * n))
    def ptr_chain(self, base, *offs):
        a = self.u64(base)
        for o in offs[:-1]:
            a = self.u64(a + o)
        return a + (offs[-1] if offs else 0)

    # -- game state block ------------------------------------------------------------------
    def state(self):
        for _ in range(100):
            s1, = struct.unpack_from("<I", self.mm, OFF_STATE)
            raw = bytes(self.mm[OFF_STATE:OFF_STATE + 0x100])
            s2, = struct.unpack_from("<I", self.mm, OFF_STATE)
            if s1 == s2 and not (s1 & 1):
                break
        f = struct.unpack_from
        return dict(
            flags=f("<I", raw, 4)[0], frame=f("<Q", raw, 8)[0],
            camPos=f("<3f", raw, 0x10), camTarget=f("<3f", raw, 0x1C), camUp=f("<3f", raw, 0x28),
            fovY=f("<f", raw, 0x34)[0], near=f("<f", raw, 0x38)[0], far=f("<f", raw, 0x3C)[0],
            aspect=f("<f", raw, 0x40)[0], playerPos=f("<3f", raw, 0x48), playerQuat=f("<4f", raw, 0x54),
            win=f("<4i", raw, 0x64), bb=f("<2I", raw, 0x74), stage=f("<I", raw, 0x7C)[0],
        )


def hexdump(data, base=0):
    for i in range(0, len(data), 16):
        chunk = data[i:i + 16]
        hx = " ".join(f"{b:02x}" for b in chunk)
        asc = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        print(f"{base + i:016x}  {hx:<48}  {asc}")


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    b = Bridge()
    cmd, args = argv[1], argv[2:]
    num = lambda s: int(s, 0)
    if cmd == "ping":
        print(b.ping())
    elif cmd == "state":
        for k, v in b.state().items():
            print(f"{k:10} {v}")
        print("heartbeat game", struct.unpack_from("<Q", b.mm, 0x10)[0], "mc", struct.unpack_from("<Q", b.mm, 0x18)[0])
    elif cmd == "read":
        a = num(args[0]); hexdump(b.read(a, num(args[1]) if len(args) > 1 else 64), a)
    elif cmd == "floats":
        a = num(args[0]); n = num(args[1]) if len(args) > 1 else 16
        vals = b.floats(a, n)
        for i in range(0, n, 4):
            print(f"{a + 4*i:016x}  " + "  ".join(f"{v:12.4f}" for v in vals[i:i + 4]))
    elif cmd == "u64":
        a = num(args[0]); n = num(args[1]) if len(args) > 1 else 8
        for i in range(n):
            print(f"{a + 8*i:016x}  {b.u64(a + 8*i):#018x}")
    elif cmd == "sig":
        for h in b.sig(" ".join(args)):
            print(f"{h:#x}")
    elif cmd == "modules":
        for name, base, size in b.modules():
            print(f"{base:#018x} {size:#10x} {name}")
    elif cmd == "query":
        print(b.query(num(args[0])))
    elif cmd == "raycast":
        v = [float(x) for x in args[:6]]
        filt = tuple(num(x) for x in args[6:9]) if len(args) >= 9 else None
        print(b.raycast(v[:3], v[3:6], filt=filt))
    elif cmd == "control":
        raw = bytes(b.mm[OFF_CONTROL:OFF_CONTROL + 0x5C])
        seq, flags, frame = struct.unpack_from("<IIQ", raw, 0)
        print(f"control seq {seq} flags {flags:#x} mcFrame {frame}")
        print("  camPos", [round(v) for v in struct.unpack_from("<3f", raw, 0x10)],
              "hunterPos", [round(v) for v in struct.unpack_from("<3f", raw, 0x38)],
              "fov", round(struct.unpack_from("<f", raw, 0x34)[0], 1))
        st = b.state()
        print(f"state flags {st['flags']:#x} zone {st['stage']} hunter {[round(v) for v in st['playerPos']]}")
    elif cmd == "fps":
        a = struct.unpack_from("<QQ", b.mm, 0x10)
        time.sleep(1.0)
        c = struct.unpack_from("<QQ", b.mm, 0x10)
        print(f"Elden Ring {c[0] - a[0]} fps, Minecraft {c[1] - a[1]} fps")
    elif cmd == "shell":
        v = [float(x) for x in args[1:7]]
        r = b.fire_shell(num(args[0]), v[:3], v[3:6])
        print("shell", r if r is None else hex(r))
    elif cmd == "ground":
        # Cast straight down through the hunter's position and compare with its feet.
        st = b.state()
        x, y, z = st["playerPos"]
        print("hunter", (round(x, 1), round(y, 1), round(z, 1)))
        print("ray   ", b.raycast((x, y + 200, z), (x, y - 1000, z)))
    else:
        print("unknown command", cmd)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
