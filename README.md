btw this was all vibecoded

# Minecraft inside Monster Hunter: World and Elden Ring (Mac + CrossOver)

Play real Minecraft (Java Edition 1.21.1 with Fabric) *inside* Monster Hunter: World or Elden
Ring on an Apple Silicon Mac. The Windows game runs in [CrossOver](https://www.codeweavers.com/crossover),
Minecraft runs natively on macOS, and the two talk to each other through shared memory while
both are running.

You play as Steve, with Minecraft's controls:

- **One picture.** The game renders its world from Minecraft's camera, and Minecraft's blocks,
  mobs and hand are drawn into the game's own frame. They're hidden behind the game's walls and
  lit by its light.
- **Real terrain.** Steve walks on the game's actual collision: floors and walls are sampled
  from the game and become invisible blocks. You can build on top of them.
- **Real fights.** The game's monsters get Minecraft hitboxes. Swords, arrows, critical hits and
  TNT hurt them in the game, and tougher monsters take more hits. Their attacks hurt you in
  Minecraft.
- **Switching.** One key (F8) hands the keyboard and mouse to the game for its menus, map and
  quests, and back.

| | Monster Hunter: World | Elden Ring |
|---|---|---|
| Graphics in CrossOver | D3D11 on DXMT | D3D12 on D3DMetal |
| How the game is started | Normally, from Steam | Offline launcher, without Easy Anti-Cheat |
| Game version supported | 15.23.00 (build 421810, the final PC patch) | App Ver. 1.17.1 (`eldenring.exe` 2.7.1.0) |
| Folder | [`monster-hunter-world/`](monster-hunter-world/README.md) | [`elden-ring/`](elden-ring/README.md) |

## Requirements

- An Apple Silicon Mac. Tested on an M2 Max with macOS 14.3.
- CrossOver 26 (tested with 26.3), with the Windows version of Steam and the game installed in a
  bottle:
  - Monster Hunter: World: a bottle named **"Monster Hunter World"**, graphics set to **DXMT**.
  - Elden Ring: a bottle named **"Elden Ring"**, graphics set to **D3DMetal**. CrossOver 26's
    D3DMetal needs macOS 15.4 or later. On macOS 14, see
    [`docs/macos-14-d3dmetal-shim.md`](docs/macos-14-d3dmetal-shim.md).

  Other bottle names and Steam library folders work too; see the game's README.
- [Homebrew](https://brew.sh) packages:
  ```sh
  brew install mingw-w64                       # cross-compiles the Windows-side DLLs
  brew install --cask temurin@21 temurin@25    # Java 21 for Minecraft, Java 25 for Gradle/Fabric Loom
  ```
- Apple's Command Line Tools (`xcode-select --install`): `git`, `make`, `clang` and Python 3, which
  the hot-reload and debug scripts use.
- Minecraft: Java Edition. Minecraft starts from Fabric's development launcher as an offline
  player, and the first start downloads Minecraft and Fabric (a few hundred MB).
- The function keys must act as F1–F12. Hold `fn`, or turn on "Use F1, F2, etc. keys as standard
  function keys" in System Settings → Keyboard.

## Getting started

Each game's README has the full steps. In short:

```sh
git clone https://github.com/justbustin/minecraft-crossover-bridge.git
cd minecraft-crossover-bridge     # every command below runs from here

# Monster Hunter: World
monster-hunter-world/scripts/install_mhw_bridge.sh      # build + install the bridge into the bottle
# start MHW from Steam and get into an area (windowed, smaller than the screen), then:
monster-hunter-world/scripts/run_minecraft.sh

# Elden Ring
elden-ring/scripts/install_er_bridge.sh                 # build + install the bridge into the bottle
open "elden-ring/Launch Elden Ring + Minecraft bridge.command"   # offline, no anti-cheat
# load your save, stand in the world, then:
elden-ring/scripts/run_minecraft.sh
```

Minecraft opens its own world ("MHW Bridge" or "ER Bridge"). It covers the game window and
takes over the keyboard and mouse once the game's character is in the world.

If you downloaded the repo as a ZIP, macOS may refuse to open the `.command` file. Run
`elden-ring/scripts/launch_er.sh` from Terminal instead, or right-click the file and choose Open.

## How it works

```
 Minecraft (native macOS, Java)                        The game (Windows x86, Rosetta + CrossOver)
 ──────────────────────────────                        ───────────────────────────────────────────
 Fabric mod  ─── camera pose, rendered frames ──▶       bridge DLL: the game renders from that pose,
                                                         Minecraft's pixels go into its frame
             ◀── terrain ray hits ───────────────       rays cast against the game's collision
             ◀── monsters: position, hitbox, HP ─       the game's own character and monster lists
             ─── hits on monsters ───────────────▶      damage applied with the game's functions
             ◀── hits on the player ─────────────       the hidden game character takes the hits
                  (shared memory: files in /tmp/mhwmc or /tmp/ermc)
```

- **Two halves.** A Fabric mod runs in Minecraft. A small Windows DLL is loaded into the game in
  place of `dinput8.dll`, and it loads the actual bridge, which can be rebuilt and swapped while
  the game runs.
- **Shared memory.** Both sides map the same files in `/tmp`. Wine shows the Mac's file system
  as drive `Z:`, and it maps files with shared pages, so a native macOS process and a Windows
  process can share memory directly.
- **Camera.** Minecraft owns the player. Each frame it sends its camera position and direction.
  The bridge overwrites the game's camera with it, so the game draws its world from where Steve
  stands.
- **Drawing into the game's frame.** Minecraft renders normally, copies its picture and depth into
  shared memory, and only then sends that frame's camera. When the game presents its frame, the
  bridge draws Minecraft's pixels into it. A per-pixel depth test against the game's depth
  buffer hides Minecraft's blocks behind the game's walls, and the brightness of the game's own
  image relights them.
- **Terrain.** Minecraft asks for batches of rays around the player. The bridge casts them
  against the game's own collision on the game thread, and every hit becomes an invisible block
  in Minecraft.
- **Monsters and damage.** The bridge publishes nearby monsters with their hitboxes and health.
  They become invisible Minecraft entities. Minecraft's hits go back into the game as damage,
  using the game's own functions. The game's character is hidden at Steve's feet as a stand-in:
  monsters target and hit it as usual, and those hits are passed to Minecraft as damage.
- **Safety.** Every game address the bridge uses is checked against its expected bytes at
  startup. If anything differs (another game version), that feature switches off instead of
  guessing. Game functions are only called from the game's own thread.

[`docs/how-it-works.md`](docs/how-it-works.md) explains each part in more depth, and what porting
it to another game would take.

## Repository layout

```
monster-hunter-world/
  mhw-bridge/     Windows DLL (C++, cross-compiled with mingw-w64): loader + bridge core
  mc-bridge/      Fabric mod for Minecraft 1.21.1
  scripts/        install, run Minecraft, hot-reload the DLL, debug tools
elden-ring/
  er-bridge/      Windows DLL
  mc-bridge/      Fabric mod
  scripts/        install, offline launcher, run Minecraft, hot-reload, debug tools
docs/             how it works; D3DMetal on macOS 14
```

## Caveats

- **Elden Ring is offline only.** The launcher starts the game without Easy Anti-Cheat, as every
  Elden Ring mod requires. Never take a modded game online. A normal Steam launch stays
  unmodded: the bridge loads only through the launcher.
- **Monster Hunter: World loads the bridge on every start** once it's installed (a DLL override
  in the bottle). Play solo: no online sessions or SOS flares while it's installed, since it
  changes monster health. Run `install_mhw_bridge.sh --remove` to undo it.
- **One game version each.** Features whose addresses don't match switch off.
- **Fan project.** It isn't affiliated with Mojang, Microsoft, Capcom, FromSoftware, Bandai
  Namco, CodeWeavers or Apple. It contains no game files or assets, only short byte patterns
  used to recognize the game's code: the bridge works on your installed games at runtime. Use
  it at your own risk and back up your saves.

## Credits

- [MinHook](https://github.com/TsudaKageyu/minhook) (BSD 2-Clause, vendored in each bridge's
  `third_party/minhook`) for the Monster Hunter: World bridge's hooks.
- [Fabric](https://fabricmc.net) (Loom, Loader, API) for the Minecraft side.
- [CrossOver](https://www.codeweavers.com/crossover) and Wine, with
  [DXMT](https://github.com/3Shain/dxmt) and Apple's D3DMetal, for running the games on macOS.
- Community research the code cites:
  - [SharpPluginLoader](https://github.com/Fexty12573/SharpPluginLoader): Monster Hunter: World's
    projectile-creation parameters.
  - [HunterPie](https://github.com/HunterPie/HunterPie): Monster Hunter: World's damage-number
    function and zone offsets.
  - [fromsoftware-rs](https://github.com/vswarte/fromsoftware-rs): Elden Ring's task-group indices.

## License

[MIT](LICENSE). MinHook keeps its own license (`*/third_party/minhook/LICENSE.txt` in each bridge).
