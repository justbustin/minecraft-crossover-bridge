# Minecraft × Elden Ring

Real Minecraft 1.21.1 (Fabric) running inside Elden Ring, which runs in CrossOver on D3DMetal. It's
the same design as the [Monster Hunter: World bridge](../monster-hunter-world/README.md). See the
[main README](../README.md) for requirements, and [how it works](../docs/how-it-works.md) for the
design.

- You walk, build, fight and die as Steve.
- Elden Ring renders the world around you, drawn from Minecraft's camera.
- Minecraft's blocks and mobs are drawn inside Elden Ring's own frame, hidden behind its walls and
  lit by its light.

Supported game version: **App Ver. 1.17.1** (`eldenring.exe` 2.7.1.0, the worldwide build).
**Offline only:** the bridge runs only when the game is started without Easy Anti-Cheat.

## Setup

1. **CrossOver bottle.** Install Steam and Elden Ring in a bottle named "Elden Ring", with the
   bottle's graphics on **D3DMetal**. CrossOver 26's D3DMetal needs macOS 15.4 or later. On macOS 14,
   follow [`docs/macos-14-d3dmetal-shim.md`](../docs/macos-14-d3dmetal-shim.md) first. The launcher
   picks the workaround up automatically.
2. **Install the bridge:**
   ```sh
   elden-ring/scripts/install_er_bridge.sh
   ```
   It builds the DLLs and copies `dinput8.dll` and `erbridge/erbridge_core.dll` into the game
   folder. It also writes `steam_appid.txt`, which lets the game start directly without going
   through Steam and Easy Anti-Cheat.
   - **Nothing loads them on a normal Steam launch:** no registry override is installed, and the
     loader stays passive unless the launcher's marker is set and no anti-cheat is present.
   - **Undo:** `elden-ring/scripts/install_er_bridge.sh --remove`.
   - **Another bottle or Steam library:** set `ER_BOTTLE` and/or `ER_GAME_DIR` for the scripts.
     `ER_GAME_DIR` is the macOS path of the folder with `eldenring.exe`, inside the bottle's
     `drive_c`. The double-click launcher doesn't read your shell profile, so with these set, run
     `elden-ring/scripts/launch_er.sh` from Terminal.
3. **Start Steam** in the "Elden Ring" bottle and log in. The game checks ownership through it.
4. **Start the game** by double-clicking **`Launch Elden Ring + Minecraft bridge.command`** in
   this folder, or by running `elden-ring/scripts/launch_er.sh`. It starts Elden Ring **offline**,
   without Easy Anti-Cheat, with the bridge enabled for that launch only.
5. **Load your save.** Once you're standing in the world, start Minecraft:
   ```sh
   elden-ring/scripts/run_minecraft.sh
   ```
   It opens the "ER Bridge" world and takes over. The world starts in creative mode: use
   `/gamemode survival` to take damage from enemies.

Logs: `/tmp/ermc/er-bridge.log` (Elden Ring side), and the Gradle console or
`elden-ring/mc-bridge/run/logs/latest.log` (Minecraft side).

## Controls (Minecraft side)

| Key | What it does |
|---|---|
| WASD, mouse, space... | Minecraft as usual. Elden Ring's camera follows you. |
| R | **Elden Ring action:** open doors, pull levers, pick up items and lost runes, touch Sites of Grace. The prompt shows under the crosshair when you look at the door or lever. E is still Minecraft's inventory. |
| F6 | Draw Minecraft inside Elden Ring (occlusion + lighting) ⇄ separate overlay window |
| F7 | Camera: Minecraft drives Elden Ring ⇄ Minecraft follows Elden Ring's camera |
| F8 | Give the keyboard and mouse to the Tarnished (menus, map travel, leveling); F8 in Elden Ring gives them back |
| F9 | Debug view: status line, hitbox outlines, HP tags |
| F10 | Stand-in on/off (the hidden Tarnished at your feet takes Elden Ring's hits for you) |

## What happens

- **Combat.** Elden Ring enemies are invisible Minecraft entities with their exact hitboxes.
  Swords, arrows, critical hits and TNT hurt them.
  - Damage scales with the enemy's toughness: a soldier takes a few sword hits, a boss dozens.
  - Minecraft mobs you spawn attack Elden Ring enemies.
  - Enemy attacks on the Tarnished become Minecraft damage, blamed on that enemy.
- **Death.** One life covers both games.
  - Die in Minecraft and the Tarnished dies too. Fall to your death in Elden Ring and Steve dies too.
  - Elden Ring's "YOU DIED" is the death screen. You respawn at your last Site of Grace, with Steve
    placed on the Tarnished.
- **Terrain.** Steve walks on Elden Ring's collision, sampled with rays: walls, floors and
  furniture, but not characters.
  - Punch a breakable prop, such as a chair or crate, to smash it in Elden Ring. The ground there
    is sampled again once it's gone.
  - Doors: look at one and the "[R] Open" prompt appears; the hidden Tarnished steps up to the
    door for you. Open doors can be walked through, and closed ones stay solid.
  - The block outline shows on Elden Ring's floors and walls too, so you can see where a block
    will go.
- **Persistence.** Blocks you place stay where you put them, per Elden Ring area. They're saved in
  the Minecraft world (`elden-ring/mc-bridge/run/saves/er-bridge`).

## Developing

- `elden-ring/scripts/reload_core.sh`: rebuild the core DLL and swap it into the running game without a
  restart. It also installs it for the next launch.
- `elden-ring/scripts/erctl.py`: live access to the bridge's debug mailbox from macOS (`ping`, `state`,
  `control`, `fps`, `read`, `sig`, `raycast`, ...). Run it without arguments for the list.
- `elden-ring/scripts/erobjects.py`: Elden Ring's map objects near the Tarnished, read live (props, doors,
  levers).
- `elden-ring/scripts/erhit.py`: push a Minecraft-style hit at an enemy, as the mod does.
- Minecraft dev commands: write lines to `/tmp/ermc/mc_cmd.txt`, for example `status`,
  `cam third`, `run <command>`, `pt debug`, `terrain reset`, `switch host|mc`, `save`, `quit`.

## Known limits

- Offline only, without Easy Anti-Cheat, as with every Elden Ring mod.
- Minecraft's HUD is drawn as is, not relit, so it stays readable. The world and your hand take
  Elden Ring's light.
- Map travel, menus and leveling happen in Elden Ring (F8).
- Drawing inside Elden Ring's frame handles frames up to 1920×1200 pixels. With a bigger window,
  Minecraft falls back to the separate overlay window and logs why.
- Supports App Ver. 1.17.1 only. Every address is checked at startup, and features switch off if
  anything differs.
