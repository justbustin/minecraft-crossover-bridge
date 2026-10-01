# Minecraft × Monster Hunter: World

Real Minecraft 1.21.1 (Fabric) running inside Monster Hunter: World, which runs in CrossOver on DXMT.
See the [main README](../README.md) for requirements, and [how it works](../docs/how-it-works.md)
for the design.

- Minecraft owns the player: you move, build, craft and fight with Minecraft's controls.
- MHW renders its world from Minecraft's camera, and Minecraft's world, hand and HUD are drawn
  inside MHW's frame, hidden behind MHW's geometry.
- Minecraft walks on MHW's real collision: ground and walls become invisible blocks.
- MHW monsters and training targets get Minecraft hitboxes. Swords, fists, arrows and TNT remove
  their HP in MHW. Each hit also fires a slinger shot from your hidden hunter, so MHW treats it
  as a real attack: the monster notices you, fights back, and dies at 0 HP.
- Your hunter stands in for Steve, hidden at his feet. Monster hits on it become Minecraft damage
  in survival mode. The hunter's own health is refilled, so it never faints.

Supported game version: **15.23.00 (build 421810)**, the final PC patch.

## Setup

1. **CrossOver bottle.** Install Steam and Monster Hunter: World in a bottle named "Monster Hunter
   World", and set the bottle's graphics to **DXMT** (bottle settings in CrossOver). Check that the
   game runs normally.
2. **Install the bridge** (once, and again after changing the loader):
   ```sh
   monster-hunter-world/scripts/install_mhw_bridge.sh
   ```
   It builds the DLLs and copies `dinput8.dll` (the loader) and `mhwbridge/mhwbridge_core.dll`
   into the game folder. It also adds a `dinput8 = native,builtin` override to the bottle, so the
   bridge loads however MHW is started. To undo all of it:
   ```sh
   monster-hunter-world/scripts/install_mhw_bridge.sh --remove
   ```
   Another bottle or Steam library: set `MHW_BOTTLE` and/or `MHW_GAME_DIR`, the macOS path of the
   folder with `MonsterHunterWorld.exe`, for this script and `reload_core.sh`.
3. **Start MHW** from Steam and get into an area: the Training Area, an expedition, a quest. Play
   windowed, smaller than the screen.
4. **Start Minecraft:**
   ```sh
   monster-hunter-world/scripts/run_minecraft.sh
   ```
   It opens, or creates, the void world "MHW Bridge". The world starts in creative mode; use
   `/gamemode survival` to take damage. Once your hunter is in the world, Minecraft's window
   sits on top of MHW's window and takes the keyboard and mouse.

Logs: `/tmp/mhwmc/mhw-bridge.log` (MHW side), and the Gradle console or
`monster-hunter-world/mc-bridge/run/logs/latest.log` (Minecraft side).

Play solo: no online sessions or SOS flares while the bridge is installed, since it changes
monster health.

## Controls (Minecraft side)

| Key | Action |
|---|---|
| F6 | Draw Minecraft inside MHW (occlusion, no drift) ⇄ separate overlay window |
| F7 | Camera: Minecraft drives MHW ⇄ Minecraft follows MHW's camera |
| F8 | Give the keyboard and mouse to your hunter (menus, quests, travel); F8 in MHW gives them back |
| F9 | Debug view: status line, hitbox outlines, every HP tag |
| F10 | Hunter stand-in on/off (hidden at your feet, takes MHW's hits for you) |

Everything else is plain Minecraft. MHW's monsters get no Minecraft decorations: HP tags and
hitbox outlines only appear in the debug view.

## Developing

- `monster-hunter-world/scripts/reload_core.sh`: rebuild the core DLL and swap it into the running game, without a
  restart. It also installs it for the next start. Changes to the loader (`proxy.cpp`) need a game
  restart.
- `monster-hunter-world/scripts/mhwctl.py`: live access to the bridge's debug mailbox from macOS (`ping`, `state`,
  `control`, `fps`, `read`, `floats`, `sig`, `raycast`, `ground`, ...). Run it without arguments
  for the list.
- `monster-hunter-world/scripts/mhwobj.py`: live game objects (`player`, `monsters` with HP, dead and target, `shells`).
- `monster-hunter-world/scripts/mccmd.py`: run Minecraft commands in the bridge world and print their chat output.
- Minecraft dev commands: write lines to `/tmp/mhwmc/mc_cmd.txt`, for example `status`,
  `cam third`, `run <command>`, `pt debug` (show MHW's depth), `pt lag N`, `terrain reset`,
  `switch mhw|mc`, `save`, `quit` (saves and exits).

## Known limits

- Minecraft damage is subtracted from the monster directly, and only the slinger shot counts as a
  real hit. Minecraft damage causes no flinches or part breaks yet.
- Terrain is a one-block voxel approximation of MHW's collision: ledges, slopes and walls are
  rounded to whole blocks, with ground heights in 1/16 steps.
- The first-person hand and the HUD aren't relit, and MHW's own HUD stays visible under
  Minecraft's.
- Drawing inside MHW's frame handles frames up to 1920×1200 pixels. With a bigger window,
  Minecraft falls back to the separate overlay window and logs why.
- Supports build 421810 only. Every hooked address is checked at startup, and features switch off
  if anything differs.
