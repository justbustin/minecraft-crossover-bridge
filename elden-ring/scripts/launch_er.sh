#!/bin/zsh
# Starts Elden Ring OFFLINE, without Easy Anti-Cheat, with the bridge enabled for this launch
# only. A normal Steam launch never loads the bridge: there is no registry override, and the
# loader also requires the ERBRIDGE=1 marker set here.
#
#   elden-ring/scripts/launch_er.sh
#
# Steam must already be running and logged in inside the "Elden Ring" bottle (the game
# checks ownership through it; steam_appid.txt in the game folder stops it from relaunching
# itself through Steam and EAC). Online features are unavailable in this mode, as with every
# Elden Ring mod.
set -euo pipefail

BOTTLE=${ER_BOTTLE:-"Elden Ring"}
CX=${CROSSOVER_WINE:-"$HOME/Applications/CrossOver.app/Contents/SharedSupport/CrossOver/bin/wine"}
[[ -x "$CX" ]] || CX="/Applications/CrossOver.app/Contents/SharedSupport/CrossOver/bin/wine"
[[ -x "$CX" ]] || { echo "CrossOver not found (looked for bin/wine in ~/Applications and /Applications; set CROSSOVER_WINE)." >&2; exit 1; }
GAME_DIR=${ER_GAME_DIR:-"$HOME/Library/Application Support/CrossOver/Bottles/$BOTTLE/drive_c/Program Files (x86)/Steam/steamapps/common/ELDEN RING/Game"}
GAME_DIR=${GAME_DIR%/}
# The same folder as Windows sees it (C: is the bottle's drive_c).
REL=${GAME_DIR#*/drive_c/}
[[ "$REL" != "$GAME_DIR" ]] || { echo "ER_GAME_DIR must be inside the bottle's drive_c: $GAME_DIR" >&2; exit 1; }
GAME_WIN="C:\\${REL//\//\\}"

[[ -f "$GAME_DIR/eldenring.exe" ]] || { echo "Elden Ring not found at: $GAME_DIR" >&2; exit 1; }
[[ -f "$GAME_DIR/erbridge/erbridge_core.dll" ]] || { echo "Bridge not installed: run elden-ring/scripts/install_er_bridge.sh" >&2; exit 1; }
if [[ "$(cat "$GAME_DIR/steam_appid.txt" 2>/dev/null | tr -d '[:space:]')" != "1245620" ]]; then
  echo "steam_appid.txt (1245620) is missing next to eldenring.exe (re-run elden-ring/scripts/install_er_bridge.sh);" >&2
  echo "without it the game relaunches itself through Steam and EAC." >&2
  exit 1
fi
if pgrep -f 'start_protected_game' >/dev/null; then
  echo "Elden Ring is already running through Easy Anti-Cheat; quit it first." >&2
  exit 1
fi

# Before macOS 15.4, CrossOver 26's stock D3DMetal doesn't load; a patched copy in
# ~/Library/d3dmetal-sonoma-shim does, if you made one (docs/macos-14-d3dmetal-shim.md).
ENVS="ERBRIDGE=1"
SHIM="$HOME/Library/d3dmetal-sonoma-shim"
if [[ -d "$SHIM/cxroot" && -f "$SHIM/external/libd3dshared.dylib" ]]; then
  ENVS="CX_ROOT=$SHIM/cxroot CX_APPLEGPTK_LIBD3DSHARED_PATH=$SHIM/external/libd3dshared.dylib $ENVS"
fi

mkdir -p /tmp/ermc
echo "Starting Elden Ring offline (no EAC) with the bridge. Log: /tmp/ermc/er-bridge.log"
exec "$CX" --bottle "$BOTTLE" --dll "dinput8=n,b" --env "$ENVS" \
  --workdir "$GAME_WIN" "$GAME_WIN\\eldenring.exe"
