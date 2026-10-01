#!/bin/zsh
# Rebuilds mhwbridge_core.dll and hot-swaps it into the running MHW (no game restart).
set -euo pipefail

ROOT=${0:A:h:h}
BOTTLE=${MHW_BOTTLE:-"Monster Hunter World"}
GAME_DIR=${MHW_GAME_DIR:-"$HOME/Library/Application Support/CrossOver/Bottles/$BOTTLE/drive_c/Program Files (x86)/Steam/steamapps/common/Monster Hunter World"}

GAME_DIR=${GAME_DIR%/}
[[ -f "$GAME_DIR/MonsterHunterWorld.exe" ]] || { echo "MHW not found at: $GAME_DIR" >&2; exit 1; }
[[ -f /tmp/mhwmc/bridge.shm ]] || { echo "No /tmp/mhwmc/bridge.shm: is MHW running with the bridge installed?" >&2; exit 1; }

make -C "$ROOT/mhw-bridge" -j8 build/mhwbridge_core.dll
mkdir -p "$GAME_DIR/mhwbridge"
cp "$ROOT/mhw-bridge/build/mhwbridge_core.dll" "$GAME_DIR/mhwbridge/mhwbridge_core.dll.new"
mv -f "$GAME_DIR/mhwbridge/mhwbridge_core.dll.new" "$GAME_DIR/mhwbridge/mhwbridge_core.dll"

python3 - <<'EOF'
import mmap, os, struct, time, sys
fd = os.open("/tmp/mhwmc/bridge.shm", os.O_RDWR)
mm = mmap.mmap(fd, 0x1000, mmap.MAP_SHARED, mmap.PROT_READ | mmap.PROT_WRITE)
req, ack, gen, status = struct.unpack_from("<IIIi", mm, 0x38)
new = (req + 1) & 0xFFFFFFFF
struct.pack_into("<I", mm, 0x38, new)
t0 = time.time()
while struct.unpack_from("<I", mm, 0x3C)[0] != new:
    if time.time() - t0 > 15:
        print("reload: no response from MHW (is it running with the new loader?)")
        sys.exit(1)
    time.sleep(0.02)
req, ack, gen, status = struct.unpack_from("<IIIi", mm, 0x38)
print(f"reload: core generation {gen}, status {status} ({'ok' if status == 1 else 'ERROR'})")
sys.exit(0 if status == 1 else 1)
EOF
tail -n 8 /tmp/mhwmc/mhw-bridge.log
