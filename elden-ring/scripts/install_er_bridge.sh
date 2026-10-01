#!/bin/zsh
# Builds the Elden Ring side of the bridge and copies it into the Elden Ring bottle.
#   elden-ring/scripts/install_er_bridge.sh            build + install
#   elden-ring/scripts/install_er_bridge.sh --remove   uninstall
#
# Unlike the MHW bridge, NO registry DLL override is installed: the files sit unused in the
# game folder and only load when elden-ring/scripts/launch_er.sh starts eldenring.exe
# offline (without Easy Anti-Cheat) with a per-launch override. Launching from Steam stays
# completely vanilla.
#
# Bottle "Elden Ring" with Steam's default library; override with ER_BOTTLE and ER_GAME_DIR
# (the macOS path of the folder that holds eldenring.exe, inside the bottle's drive_c).
set -euo pipefail

ROOT=${0:A:h:h:h}
ER=$ROOT/elden-ring
BOTTLE=${ER_BOTTLE:-"Elden Ring"}
GAME_DIR=${ER_GAME_DIR:-"$HOME/Library/Application Support/CrossOver/Bottles/$BOTTLE/drive_c/Program Files (x86)/Steam/steamapps/common/ELDEN RING/Game"}
GAME_DIR=${GAME_DIR%/}

if [[ ! -f "$GAME_DIR/eldenring.exe" ]]; then
  echo "Elden Ring not found at: $GAME_DIR" >&2
  exit 1
fi

if [[ "${1:-}" == "--remove" ]]; then
  if [[ -f "$GAME_DIR/dinput8.dll" ]] && grep -q "erbridge" "$GAME_DIR/dinput8.dll"; then
    rm -f "$GAME_DIR/dinput8.dll"
  fi
  [[ -f "$GAME_DIR/erbridge/steam_appid-added" ]] && rm -f "$GAME_DIR/steam_appid.txt"
  rm -rf "$GAME_DIR/erbridge"
  echo "Removed er-bridge from $BOTTLE"
  exit 0
fi

if [[ -f "$GAME_DIR/dinput8.dll" ]] && ! grep -q "erbridge" "$GAME_DIR/dinput8.dll"; then
  echo "A different dinput8.dll is already installed (another mod loader?)." >&2
  echo "Back it up/remove it first: $GAME_DIR/dinput8.dll" >&2
  exit 1
fi

make -C "$ER/er-bridge" -j8
mkdir -p "$GAME_DIR/erbridge"
# Copy-then-rename: a running game keeps its mapped copy of the old file intact.
cp "$ER/er-bridge/build/dinput8.dll" "$GAME_DIR/dinput8.dll.new"
mv -f "$GAME_DIR/dinput8.dll.new" "$GAME_DIR/dinput8.dll"
cp "$ER/er-bridge/build/erbridge_core.dll" "$GAME_DIR/erbridge/erbridge_core.dll.new"
mv -f "$GAME_DIR/erbridge/erbridge_core.dll.new" "$GAME_DIR/erbridge/erbridge_core.dll"
# Lets launch_er.sh start eldenring.exe directly: without it the game restarts itself through
# Steam and Easy Anti-Cheat.
if [[ ! -f "$GAME_DIR/steam_appid.txt" ]]; then
  echo 1245620 > "$GAME_DIR/steam_appid.txt"
  touch "$GAME_DIR/erbridge/steam_appid-added"   # so --remove takes it out again
fi
mkdir -p /tmp/ermc
echo "Installed er-bridge into: $GAME_DIR"
echo "Start the game with: elden-ring/scripts/launch_er.sh   (log: /tmp/ermc/er-bridge.log)"
