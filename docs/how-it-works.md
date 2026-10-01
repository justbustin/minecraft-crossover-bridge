# How it works

This is a tour of the moving parts, for anyone who wants to understand the bridge or port it to
another game. The code is the reference: most source files start with a comment that explains
what they do, and the shared-memory layout is defined in each bridge's `include/bridge_protocol.h`.

## Two programs, one shared memory

On an Apple Silicon Mac with CrossOver, the two games live in different worlds:

- **Minecraft** is a native macOS (ARM) Java program. Our Fabric mod runs inside it.
- **The game** is a Windows x86-64 program. It runs under Rosetta 2 and Wine (CrossOver), and it
  renders through DXMT (Direct3D 11) or Apple's D3DMetal (Direct3D 12), both of which draw with
  Metal.

They can still share memory directly:

- The Windows side opens `Z:\tmp\mhwmc\bridge.shm` (or `\tmp\ermc\` for Elden Ring). Wine shows
  the Mac's root folder as drive `Z:`, so this is the macOS file `/tmp/mhwmc/bridge.shm`.
- Wine implements file mappings with `mmap(MAP_SHARED)`, and the mod maps the same file. Both
  processes then read and write the same physical pages, with no copies and no sockets.

The mappings are:

- **`bridge.shm` (8 MB)**: a header, the game state, Minecraft's control block, terrain rays and
  hits, the entity table, a damage queue in each direction, and a debug mailbox. Small blocks
  use sequence counters (seqlocks), so neither side ever reads a half-written record.
- **`frames.shm`**: Minecraft's rendered frames, triple-buffered. It's about 80 MB for Monster
  Hunter: World and 110 MB for Elden Ring, which sends the hand and the HUD as separate layers.

`bridge_protocol.h` is the authoritative layout. `mc-bridge/.../link/Protocol.java` mirrors it on
the Java side.

## The Windows side: a loader and a core

The game loads `dinput8.dll` from its own folder. That works because CrossOver is told to prefer a
native `dinput8` over Wine's builtin one: a registry override for Monster Hunter: World, and a
per-launch override for Elden Ring.

- **The loader** (`dinput8.dll`) is tiny and never changes. It forwards the real DirectInput
  functions to Wine's own `dinput8` and loads the core from a subfolder.
- **The core** (`mhwbridge_core.dll` / `erbridge_core.dll`) does all the work. `reload_core.sh`
  rebuilds it and asks the loader to swap it while the game keeps running. Hooks point at small
  stubs in a memory page that outlives the core. While no core is loaded, the stubs jump
  straight back to the game's own code, so a swap never leaves the game calling freed memory.

Rules the core lives by:

- Every game address is checked against the bytes expected there (a "signature") before use.
  Anything unexpected switches that feature off; the code never guesses.
- Game functions are only called from the game's own thread, from a per-frame hook. Other threads
  only exchange data through shared memory.

## The Minecraft side: a Fabric mod

The mod (`mc-bridge/`) opens its own void world, created in creative mode. Switch to survival
(`/gamemode survival`) to take damage. The mod keeps that world in sync with the game:

- **Player and camera.** Minecraft owns the player. Every frame the mod writes the camera's
  position, direction and field of view, plus the player's position, into the control block.
- **Terrain.** The server side of the mod keeps invisible "terrain" blocks under and around the
  player. Each column's top matches the game's ground to 1/16 of a block, and you can build on
  them like any other block.
- **Entities.** Each monster the game reports becomes an invisible Minecraft entity with the
  monster's hitbox and health. Minecraft's weapons, arrows, TNT and mobs hit those entities
  normally, and the mod forwards those hits to the game.
- **Display and input.** Minecraft's window becomes transparent and borderless, sits on top of
  the game's window, and keeps the keyboard and mouse.

## Each part in more detail

### Camera

The bridge overwrites the game's camera every frame with Minecraft's pose, after the game has
computed its own, so the game renders its world from Steve's eyes. In Monster Hunter: World that's
a hook on the camera update. In Elden Ring it's a task registered with the game's own task
scheduler, which runs on the game's main thread.

### Drawing Minecraft into the game's frame ("passthrough")

Two windows on top of each other would drift apart by a frame or two, and Minecraft's blocks
could never hide behind the game's walls. Instead, each frame:

1. Minecraft renders its world, then reads back the color and depth. It reads back the hand and
   HUD separately, as a transparent layer.
2. Those pixels go into a `frames.shm` slot. Only then does Minecraft hand the game that frame's
   camera pose.
3. The game renders its frame from that pose. When the game presents it (the Direct3D
   `Present` call), the bridge draws Minecraft's frame into it with a small shader:
   - **Occlusion:** a per-pixel depth test against the game's own scene depth buffer, so the
     game's walls hide Minecraft's blocks. The bridge finds that buffer by watching which
     depth buffer the game clears each frame.
   - **Lighting:** Minecraft's pixels take the local brightness of a blurred copy of the game's
     image, then fade into the distance haze.
   - The hand is relit but never hidden; the HUD is drawn as is.
4. Minecraft's own window shows nothing in this mode and only keeps the input.

F6 switches to the fallback "overlay" mode: Minecraft draws into its transparent window on top of
the game, without occlusion.

How the `Present` hook is installed differs:

- **Monster Hunter: World (DXMT):** an inline hook (MinHook) on the swapchain's `Present`,
  found through a throwaway swapchain.
- **Elden Ring (D3DMetal):** a patch to D3DMetal's writable swapchain function table. Elden Ring's
  executable protects its own code, so the Elden Ring bridge doesn't change any code. Its
  per-frame work runs as tasks in the game's own scheduler.

### Terrain

Minecraft can't see the game's world, so it asks:

1. The mod sends batches of downward rays around the player.
2. The bridge casts them against the game's own collision system, on the game thread.
3. Every hit becomes a column of invisible terrain blocks.

The rays skip characters, so monsters don't become terrain. In Elden Ring, breaking a prop (a
crate, a chair) or opening a door triggers a fresh sample of that spot.

### Monsters and damage

Each tick, the bridge reads the game's own lists of characters and publishes the nearby ones to
the entity table: position, hitbox, health, and whether they're hostile. Then:

- **Hits from Minecraft:** when Minecraft hurts one of the proxy entities, the hit goes into a
  damage queue. On the game thread, the bridge applies it through the game's own health
  functions, so the game credits the player and the monster reacts and fights back. Damage is
  converted from Minecraft's scale to the game's, and tougher monsters take more hits.
- **Hits from the game:** the game's own character (the hunter or the Tarnished) stands in for
  Steve. It's hidden at Steve's feet, so monsters target it and their attacks land on it. The
  bridge keeps it alive and reports every hit to Minecraft as damage from that monster.
- **Death:** the two games differ.
  - **Elden Ring** shares one life. Dying in Minecraft kills the Tarnished, and dying in the game
    kills Steve. The game's own death screen and respawn at the last Site of Grace are used, and
    Steve is put back on the Tarnished.
  - **Monster Hunter: World:** the hunter never faints while standing in, because its health is
    refilled. A Minecraft death respawns Steve right back on the hunter with his inventory kept,
    so it never costs a cart.

### Coordinates

Each bridge publishes positions in the game's own units, with a scale field (`unitsPerMeter`):
Monster Hunter: World uses centimetres, Elden Ring metres. Each game also has its own axes and
handedness: Elden Ring is left-handed, so Minecraft's Z is its -Z. Large or shifting worlds use a stable origin per zone,
and the Minecraft side maps all of it onto block coordinates. Blocks you place are saved per zone
in the Minecraft world.

### Switching between the games

F8 hands the keyboard and mouse to the game, for its menus, quests, inventory or map travel. F8 in
the game hands them back. The bridge watches the key with `GetAsyncKeyState` and tells Minecraft
through shared memory.

## A note on the debug mailbox

`bridge.shm` includes a debug mailbox that the Python tools (`mhwctl.py`, `erctl.py`) use to read
and write the game's memory. Like the rest of the shared memory, it lives in `/tmp`, so any
program running as your user can use it while the game runs. That's fine on a personal Mac, but
don't leave the bridge installed on a machine you share with people you don't trust.

## Porting it to another game

The Minecraft side is mostly game-independent. For a new game, the Windows side needs these
pieces, each found by reverse engineering that exact game version:

| Need | What it's for |
|---|---|
| A per-frame hook on the game thread | Somewhere safe to call the game every frame |
| The camera (position, rotation, FOV) and where to override it | Minecraft drives the view |
| A collision raycast function | Terrain |
| The character list, positions, hitboxes, health and a damage function | Fights |
| The player character, and a way to hide it and keep it alive | The stand-in |
| The swapchain's `Present` and the scene depth buffer | Drawing into the game's frame |

The two ports here show two styles. Monster Hunter: World uses inline hooks on a game without
code protection. Elden Ring's code is protected and its anti-cheat must stay off, so that port
avoids patching game code.
