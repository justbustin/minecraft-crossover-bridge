#!/usr/bin/env python3
"""Views of live MHW game objects (421810), through the bridge's debug mailbox (read-only except `sleep`).

    python3 monster-hunter-world/scripts/mhwobj.py player        # master hunter: address, position, HP, slinger shells
    python3 monster-hunter-world/scripts/mhwobj.py monsters      # every sEnemy entry: species, HP, dead flag, AI target
    python3 monster-hunter-world/scripts/mhwobj.py shells        # the hunter's slinger shell list (index -> .shlp path)
    python3 monster-hunter-world/scripts/mhwobj.py actions [em]  # a monster's own actions (set 1): index -> class
    python3 monster-hunter-world/scripts/mhwobj.py sleep [em|name]  # put a monster to sleep (nearest large one by default)
    python3 monster-hunter-world/scripts/mhwobj.py units         # sUnit lines: unit count and classes per line
    python3 monster-hunter-world/scripts/mhwobj.py liveshells    # live shell units (projectiles, breath...): class, position, owner

Also importable: `sys.path.insert(0, "monster-hunter-world/scripts"); from mhwobj import Obj`.
"""
import math
import struct
import sys

sys.path.insert(0, __import__("os").path.dirname(__file__))
import mhwctl  # noqa: E402

S_PLAYER = 0x14500ECA0      # static sPlayer*
S_ENEMY = 0x14500CF40       # static sEnemy*: +0x38, 128 inline AI pointers
PLAYER_POS = 0x160
PLAYER_HEALTH = 0x7630      # cpHealthManager*: +0x60 max, +0x64 current
PLAYER_SLINGER_SHLL = 0x56E8  # rShellParamList: +0xA8 entries {u64, rShellParam*}, +0xB0 count
ENEMY_HEALTH = 0x7670
ENEMY_SPECIES = 0x12280
ENEMY_AI = 0x12278          # AI data; AI+0x138 -> uEnemy
ENEMY_ACTION_CTRL = 0x61C8  # cActionController (inline): +0xAC current action {int set, int id}
AI_STATE_FLAGS = 0x14780    # bit 2: dead
AI_TARGET_HOLDER = 0xAA8    # [[AI+0xAA8]+0x5D0] = current target (SPL Monster.SetTarget)
ENEMY_MODEL_PATH = 0x2A0    # -> resource; "em\\em002\\00\\mod\\em002_00" at +0x0C
ENEMY_CONDITIONS = 0x1BC40  # cEmConditionParam*[25] by condition id; +0x150 active, +0x158 id
COND_SLEEP = 3
ACTION_SET1 = 0x78          # in the action controller: the monster's own actions, void** + u32 count at +8
S_UNIT = 0x1451238C8        # static sUnit*: lines of 0xF8 bytes, top unit at +0x80 (cUnit +0x30 = next)
UNIT_LINES = 64
EM_NAMES = {
    "em001": "Rathian", "em002": "Rathalos", "em007": "Diablos", "em011": "Kirin", "em024": "Kushala Daora",
    "em026": "Lunastra", "em027": "Teostra", "em032": "Tigrex", "em037": "Nargacuga", "em043": "Deviljho",
    "em044": "Barroth", "em045": "Uragaan", "em057": "Zinogre", "em063": "Brachydios", "em080": "Glavenus",
    "em100": "Anjanath", "em101": "Great Jagras", "em102": "Pukei-Pukei", "em103": "Nergigante",
    "em105": "Xeno'jiiva", "em107": "Kulu-Ya-Ku", "em108": "Jyuratodus", "em109": "Tobi-Kadachi",
    "em110": "Paolumu", "em111": "Legiana", "em112": "Great Girros", "em113": "Odogaron", "em114": "Radobaan",
    "em115": "Vaal Hazak", "em116": "Dodogama", "em117": "Kulve Taroth", "em118": "Bazelgeuse",
    "em120": "Tzitzi-Ya-Ku", "em121": "Behemoth", "em122": "Beotodus", "em123": "Banbaro", "em124": "Velkhana",
    "em125": "Namielle", "em126": "Shara Ishvalda", "em127": "Leshen",
}


class Obj:
    def __init__(self, b=None):
        self.b = b or mhwctl.Bridge()

    def q(self, a):
        return struct.unpack("<Q", self.b.read(a, 8))[0]

    def d(self, a):
        return struct.unpack("<I", self.b.read(a, 4))[0]

    def f(self, a, n=1):
        v = struct.unpack(f"<{n}f", self.b.read(a, 4 * n))
        return v if n > 1 else v[0]

    def cstr(self, a, n=128):
        return self.b.read(a, n).split(b"\0")[0].decode(errors="replace")

    def master_player(self):
        """Same walk as FindMasterPlayer (0x141B42010): 0x18 slots of 0x740 bytes from sPlayer+0x50."""
        sp = self.q(S_PLAYER)
        me = self.q(sp + 0xAE40)
        slot = sp + 0x50
        for _ in range(0x18):
            used = any(struct.unpack("<i", self.b.read(slot + 4 * k, 4))[0] >= 0 for k in range(2))
            if used and self.q(slot - 8) == me:
                return self.q(slot + 8)
            slot += 0x740
        return 0

    def alive(self, em):
        """A live monster, as the bridge DLL counts them: its AI and unit are active, and it isn't dead."""
        try:
            ai = self.q(em + ENEMY_AI)
            active = lambda u: (self.b.read(u + 0x0C, 1)[0] & 0xE) != 0
            return bool(ai) and active(ai) and active(em) and not self.d(ai + AI_STATE_FLAGS) & 4
        except mhwctl.BridgeError:
            return False

    def enemies(self):
        """[(ai, uEnemy)] for every occupied sEnemy slot (dead ones included)."""
        se = self.q(S_ENEMY)
        out = []
        for i in range(128):
            ai = self.q(se + 0x38 + i * 8)
            if ai:
                em = self.q(ai + 0x138)
                if em:
                    out.append((ai, em))
        return out

    def enemy_info(self, em):
        hm = self.q(em + ENEMY_HEALTH)
        ai = self.q(em + ENEMY_AI)
        holder = self.q(ai + AI_TARGET_HOLDER) if ai else 0
        return {
            "species": self.d(em + ENEMY_SPECIES),
            "pos": self.f(em + 0x160, 3),
            "hp": self.f(hm + 0x64) if hm else None,
            "maxHp": self.f(hm + 0x60) if hm else None,
            "dead": bool(ai and self.d(ai + AI_STATE_FLAGS) & 4),
            "target": self.q(holder + 0x5D0) if holder else 0,
            "action": struct.unpack("<ii", self.b.read(em + ENEMY_ACTION_CTRL + 0xAC, 8)),
        }

    def class_name(self, obj):
        """MT Framework class of a live object: vtable slot 4 is GetDTI (`lea rax,[rip+X]; ret`),
        and the descriptor's name is at +0x08."""
        try:
            fn = self.q(self.q(obj) + 8 * 4)
            code = self.b.read(fn, 8)
            if code[:3] != b"\x48\x8d\x05" or code[7] != 0xC3:
                return None
            dti = fn + 7 + struct.unpack_from("<i", code, 3)[0]
            return self.cstr(self.q(dti + 8), 96)
        except mhwctl.BridgeError:
            return None  # a unit freed while we walked the list

    def monster_code(self, em):
        """"em002", "ems062"... from the model path."""
        res = self.q(em + ENEMY_MODEL_PATH)
        path = self.cstr(res + 0x0C, 64) if res else ""
        parts = path.split("\\")
        return parts[1].split("_")[0] if len(parts) > 1 else "?"

    def actions(self, em):
        """Class names of the monster's own actions (set 1), by index."""
        ctrl = em + ENEMY_ACTION_CTRL
        lst, count = self.q(ctrl + ACTION_SET1), self.d(ctrl + ACTION_SET1 + 8)
        if not lst or count > 4096:
            raise mhwctl.BridgeError(f"{em:#x} has no believable action list ({count} entries)")
        ptrs = struct.unpack(f"<{count}Q", self.b.read(lst, 8 * count))
        return [self.class_name(p) if p else None for p in ptrs]

    def units(self):
        """[(line, unit)] for every unit in sUnit's lines."""
        su = self.q(S_UNIT)
        out = []
        for line in range(UNIT_LINES):
            u = self.q(su + line * 0xF8 + 0x80)
            n = 0
            while u and n < 10000:
                out.append((line, u))
                try:
                    u = self.q(u + 0x30)
                except mhwctl.BridgeError:
                    break  # the list changed under us (MHW's thread owns it)
                n += 1
        return out

    def slinger_shells(self):
        pl = self.master_player()
        shll = self.q(pl + PLAYER_SLINGER_SHLL)
        entries, count = self.q(shll + 0xA8), self.d(shll + 0xB0)
        out = []
        for i in range(count):
            p = self.q(entries + i * 16 + 8)
            out.append((i, self.cstr(p + 0x0C, 96) if p else ""))
        return out


def nearest_large(o):
    """The nearest living large monster (em0xx/em1xx) to the hunter."""
    pl = o.master_player()
    pp = o.f(pl + PLAYER_POS, 3)
    best = None
    for ai, em in o.enemies():
        try:
            e = o.enemy_info(em)
            code = o.monster_code(em)
        except mhwctl.BridgeError:
            continue
        if not o.alive(em) or not code.startswith("em") or code.startswith("ems"):
            continue
        d = math.dist(pp, e["pos"])
        if best is None or d < best[0]:
            best = (d, em)
    if best is None:
        raise SystemExit("no living large monster")
    return best[1]


def sleep(o, which):
    """Puts a monster to sleep the way MHW does: its sleep condition activates (timer, HUD,
    wakes on the next hit) and it starts its own DamageSleep action (DamageSleepFly in the air)."""
    if which is None:
        em = nearest_large(o)
    elif which.lower().startswith("0x"):
        em = int(which, 16)
    else:
        def named(em):
            try:
                return EM_NAMES.get(o.monster_code(em), "").lower() == which.lower() and o.alive(em)
            except mhwctl.BridgeError:
                return False
        em = next((em for ai, em in o.enemies() if named(em)), 0)
        if not em:
            raise SystemExit(f"no living {which} here")
    names = o.actions(em)
    cur_set, cur = o.enemy_info(em)["action"]
    cur_name = (names[cur] if cur_set == 1 and 0 <= cur < len(names) else None) or ""
    airborne = any(k in cur_name for k in ("Fly", "Glide", "Air"))

    def find(pred):
        return next((i for i, n in enumerate(names) if n and pred(n.split("::")[-1])), None)
    idx = find(lambda n: n == "DamageSleepFly") if airborne else None
    if idx is None:
        idx = find(lambda n: n == "DamageSleep")
    if idx is None:  # e.g. Kulve Taroth: DamageSleepL / DamageSleepR
        idx = find(lambda n: n.startswith("DamageSleep") and not any(k in n for k in ("End", "Fly", "Loop", "Air")))
    code = o.monster_code(em)
    if idx is None:
        raise SystemExit(f"{EM_NAMES.get(code, code)} has no sleep action")
    print(f"{EM_NAMES.get(code, code)} {em:#x}: current action {cur} {cur_name}; launching {idx} {names[idx]}")
    st = o.b.monster(em, COND_SLEEP, idx)
    cond = o.q(em + ENEMY_CONDITIONS + COND_SLEEP * 8)
    active = o.d(cond + 0x150) if cond else None
    print(f"status {st}; sleep condition active {active}; action now {o.enemy_info(em)['action']}")
    return 0 if st == 0 else 1


def main(argv):
    o = Obj()
    cmd = argv[1] if len(argv) > 1 else "monsters"
    if cmd == "player":
        pl = o.master_player()
        hm = o.q(pl + PLAYER_HEALTH)
        print(f"hunter {pl:#x} pos {[round(v) for v in o.f(pl + PLAYER_POS, 3)]} "
              f"hp {o.f(hm + 0x64):.0f}/{o.f(hm + 0x60):.0f}")
    elif cmd == "monsters":
        pl = o.master_player()
        pp = o.f(pl + PLAYER_POS, 3)
        rows = []
        for ai, em in o.enemies():
            try:
                e = o.enemy_info(em)
            except mhwctl.BridgeError:
                continue
            rows.append((math.dist(pp, e["pos"]) / 100, em, e))
        for dist, em, e in sorted(rows, key=lambda r: r[0]):
            print(f"{em:#x} species {e['species']:3} {dist:6.1f} m  hp {e['hp']:.0f}/{e['maxHp']:.0f}"
                  f"{'  DEAD' if e['dead'] else ''}{'  targets hunter' if e['target'] == pl else ''}  action {e['action']}")
    elif cmd == "shells":
        for i, path in o.slinger_shells():
            if path:
                print(i, path)
    elif cmd == "actions":
        em = int(argv[2], 0) if len(argv) > 2 else nearest_large(o)
        for i, name in enumerate(o.actions(em)):
            print(i, name)
    elif cmd == "sleep":
        return sleep(o, argv[2] if len(argv) > 2 else None)
    elif cmd == "units":
        import collections
        per = collections.defaultdict(collections.Counter)
        for line, u in o.units():
            per[line][o.class_name(u) or "?"] += 1
        for line in sorted(per):
            c = per[line]
            print(f"line {line:2}: {sum(c.values()):4}  " + ", ".join(f"{k} x{v}" for k, v in c.most_common(8)))
    elif cmd == "liveshells":
        for line, u in o.units():
            name = o.class_name(u) or "?"
            if "Shell" in name:
                try:
                    pos = o.f(u + 0x160, 3)
                    owner = o.q(u + 0xB58)
                except mhwctl.BridgeError:
                    continue  # freed while we listed (shells are short-lived)
                print(f"line {line:2} {u:#x} {name:32} pos {[round(v) for v in pos]} owner(+0xB58) {owner:#x}")
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
