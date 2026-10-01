#!/bin/zsh
# Rebuilds erbridge_core.dll and hot-swaps it into the running Elden Ring (no game restart).
# Also installs it for the next launch.
set -euo pipefail

ROOT=${0:A:h:h:h}
ER=$ROOT/elden-ring
BOTTLE=${ER_BOTTLE:-"Elden Ring"}
GAME_DIR=${ER_GAME_DIR:-"$HOME/Library/Application Support/CrossOver/Bottles/$BOTTLE/drive_c/Program Files (x86)/Steam/steamapps/common/ELDEN RING/Game"}

GAME_DIR=${GAME_DIR%/}
[[ -f "$GAME_DIR/eldenring.exe" ]] || { echo "Elden Ring not found at: $GAME_DIR" >&2; exit 1; }
[[ -f /tmp/ermc/bridge.shm ]] || { echo "No /tmp/ermc/bridge.shm: is Elden Ring running through launch_er.sh?" >&2; exit 1; }

make -C "$ER/er-bridge" -j8 build/erbridge_core.dll
mkdir -p "$GAME_DIR/erbridge"
cp "$ER/er-bridge/build/erbridge_core.dll" "$GAME_DIR/erbridge/erbridge_core.dll.new"
mv -f "$GAME_DIR/erbridge/erbridge_core.dll.new" "$GAME_DIR/erbridge/erbridge_core.dll"

python3 - <<'PY'
import mmap, os, struct, time, sys
fd = os.open("/tmp/ermc/bridge.shm", os.O_RDWR)
mm = mmap.mmap(fd, 0x1000, mmap.MAP_SHARED, mmap.PROT_READ | mmap.PROT_WRITE)
req, ack, gen, status = struct.unpack_from("<IIIi", mm, 0x38)
new = (req + 1) & 0xFFFFFFFF
struct.pack_into("<I", mm, 0x38, new)
t0 = time.time()
while struct.unpack_from("<I", mm, 0x3C)[0] != new:
    if time.time() - t0 > 15:
        print("reload: no response from Elden Ring (is it running via launch_er.sh?)")
        sys.exit(1)
    time.sleep(0.02)
req, ack, gen, status = struct.unpack_from("<IIIi", mm, 0x38)
print(f"reload: core generation {gen}, status {status} ({'ok' if status == 1 else 'ERROR'})")
sys.exit(0 if status == 1 else 1)
PY
tail -n 8 /tmp/ermc/er-bridge.log
