#!/bin/zsh
# Builds the MHW-side bridge and installs it into the CrossOver bottle.
#   monster-hunter-world/scripts/install_mhw_bridge.sh            build + install (restart MHW to pick up a new loader)
#   monster-hunter-world/scripts/install_mhw_bridge.sh --remove   uninstall (restores vanilla behaviour)
# For iterating on the core while MHW runs, use reload_core.sh instead.
#
# Bottle "Monster Hunter World" with Steam's default library; override with MHW_BOTTLE and
# MHW_GAME_DIR (the macOS path of the folder that holds MonsterHunterWorld.exe). CROSSOVER_WINE
# overrides the path of CrossOver's bin/wine.
set -euo pipefail

ROOT=${0:A:h:h}
BOTTLE=${MHW_BOTTLE:-"Monster Hunter World"}
CX=${CROSSOVER_WINE:-"$HOME/Applications/CrossOver.app/Contents/SharedSupport/CrossOver/bin/wine"}
[[ -x "$CX" ]] || CX="/Applications/CrossOver.app/Contents/SharedSupport/CrossOver/bin/wine"
GAME_DIR=${MHW_GAME_DIR:-"$HOME/Library/Application Support/CrossOver/Bottles/$BOTTLE/drive_c/Program Files (x86)/Steam/steamapps/common/Monster Hunter World"}
GAME_DIR=${GAME_DIR%/}
# Per-exe override plus a bottle-wide one, so the bridge loads no matter how MHW is started
# (Steam, CrossOver shortcut, direct exe). Other programs in the bottle have no dinput8.dll
# next to them, so they keep using Wine's builtin one.
KEYS=('HKCU\Software\Wine\AppDefaults\MonsterHunterWorld.exe\DllOverrides' 'HKCU\Software\Wine\DllOverrides')
# The overrides this script added (and --remove takes out again); ones that were already there stay.
ADDED_FILE="$GAME_DIR/mhwbridge/overrides-added.txt"

if [[ ! -f "$GAME_DIR/MonsterHunterWorld.exe" ]]; then
  echo "MHW not found at: $GAME_DIR" >&2
  exit 1
fi
if [[ ! -x "$CX" ]]; then
  echo "CrossOver not found (looked for bin/wine in ~/Applications and /Applications; set CROSSOVER_WINE)." >&2
  exit 1
fi

if [[ "${1:-}" == "--remove" ]]; then
  if [[ -f "$GAME_DIR/dinput8.dll" ]] && ! grep -q "mhwbridge" "$GAME_DIR/dinput8.dll"; then
    echo "Left $GAME_DIR/dinput8.dll alone: it isn't the bridge's (another mod loader?)." >&2
  else
    rm -f "$GAME_DIR/dinput8.dll"
  fi
  if [[ -f "$ADDED_FILE" ]]; then
    while IFS= read -r KEY; do
      [[ -n "$KEY" ]] || continue
      "$CX" --bottle "$BOTTLE" reg delete "$KEY" /v dinput8 /f >/dev/null 2>&1 || true
    done < "$ADDED_FILE"
  fi
  rm -rf "$GAME_DIR/mhwbridge"
  echo "Removed mhw-bridge from $BOTTLE"
  exit 0
fi

if [[ -f "$GAME_DIR/dinput8.dll" ]] && ! grep -q "mhwbridge" "$GAME_DIR/dinput8.dll"; then
  echo "A different dinput8.dll is already installed (another mod loader?)." >&2
  echo "Back it up/remove it first: $GAME_DIR/dinput8.dll" >&2
  exit 1
fi

make -C "$ROOT/mhw-bridge" -j8
mkdir -p "$GAME_DIR/mhwbridge"
# Copy-then-rename: a running MHW keeps its mapped copy of the old file intact.
cp "$ROOT/mhw-bridge/build/dinput8.dll" "$GAME_DIR/dinput8.dll.new"
mv -f "$GAME_DIR/dinput8.dll.new" "$GAME_DIR/dinput8.dll"
cp "$ROOT/mhw-bridge/build/mhwbridge_core.dll" "$GAME_DIR/mhwbridge/mhwbridge_core.dll.new"
mv -f "$GAME_DIR/mhwbridge/mhwbridge_core.dll.new" "$GAME_DIR/mhwbridge/mhwbridge_core.dll"
# Wine prefers its builtin dinput8; make MHW load ours from the game folder instead.
touch "$ADDED_FILE"
for KEY in "${KEYS[@]}"; do
  if ! "$CX" --bottle "$BOTTLE" reg query "$KEY" /v dinput8 >/dev/null 2>&1; then
    grep -qxF "$KEY" "$ADDED_FILE" || print -r -- "$KEY" >> "$ADDED_FILE"
  fi
  OUT=$("$CX" --bottle "$BOTTLE" reg add "$KEY" /v dinput8 /t REG_SZ /d "native,builtin" /f 2>&1) || {
    print -r -- "$OUT" | grep -v -e fixme -e msync >&2 || true
    echo "Couldn't set the dinput8 DLL override ($KEY) in the bottle \"$BOTTLE\"." >&2
    exit 1
  }
done
mkdir -p /tmp/mhwmc
echo "Installed mhw-bridge into: $GAME_DIR"
echo "Log: /tmp/mhwmc/mhw-bridge.log"
