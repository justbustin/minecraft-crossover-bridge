# Elden Ring on macOS 14: a D3DMetal workaround

**You only need this on macOS older than 15.4** (for example Sonoma 14.x) with CrossOver 26.
Updating macOS to 15.4 or later removes the need for it. It was worked out with CrossOver 26.3 on
an M2 Max running macOS 14.3.

The steps patch a private copy of the D3DMetal libraries from your own CrossOver installation,
for your own use. Nothing from Apple or CodeWeavers is redistributed here.

## Symptom

Elden Ring, or any DirectX 12 game, crashes right at launch in a D3DMetal bottle:

- A Wine dialog: "The program eldenring.exe has encountered a serious problem".
- In Steam's `logs/gameprocess_log.txt`: `start_protected_game.exe` exits with `-1073741819`
  (`0xC0000005`) a few seconds after launch.
- With `--debugmsg +seh`, Wine's log shows:
  `Assertion failed: (GFXTHandle && "Failed to dlopen D3DMetal")`.

## Cause

CrossOver 26 bundles D3DMetal 3.0, in
`CrossOver.app/Contents/SharedSupport/CrossOver/lib64/apple_gptk/external/`. One library in it,
`D3DMetal.framework/Versions/A/Resources/libdxccontainer.dylib`, is built for macOS 15.4. It
imports one C++ library function that older macOS versions don't have:

```
weak-def symbol not found '__ZdlPvSt19__type_descriptor_t'
    = operator delete(void*, std::__type_descriptor_t)
```

Everything else in the framework loads fine on macOS 14.

## The fix

A small wrapper library supplies the missing function, and a mirror of CrossOver's folder makes
Wine load the patched copy. Nothing inside `CrossOver.app` or the bottle is modified.
Everything lives in `~/Library/d3dmetal-sonoma-shim/`, about 63 MB:

| Path | What it is |
|---|---|
| `external/` | Copy of the app's `lib64/apple_gptk/external` (libd3dshared + D3DMetal.framework) |
| `external/D3DMetal.framework/Versions/A/Resources/libdxccontainer_real.dylib` | Apple's original, renamed, its install name changed to `@loader_path/libdxccontainer_real.dylib`, ad-hoc re-signed |
| `external/D3DMetal.framework/Versions/A/Resources/libdxccontainer.dylib` | The wrapper: defines the missing `operator delete` (calls `free`) and re-exports the real library |
| `wine/` | Copy of the app's `lib64/apple_gptk/wine` (the `d3d12`/`dxgi`/`d3d11` bridge modules) |
| `cxroot/` | Mirror of CrossOver's root: symlinks to the app for everything, except `lib64/apple_gptk/{external,wine}`, which point at the copies above |
| `shim.c` | Source of the wrapper |

Build it (needs the Xcode Command Line Tools for `clang`):

```zsh
P="$HOME/Library/d3dmetal-sonoma-shim"
CX="$HOME/Applications/CrossOver.app/Contents/SharedSupport/CrossOver"
[[ -d "$CX" ]] || CX="/Applications/CrossOver.app/Contents/SharedSupport/CrossOver"
rm -rf "$P" && mkdir -p "$P/cxroot/lib64/apple_gptk"

cat > "$P/shim.c" <<'EOF'
#include <stdlib.h>
/* operator delete(void*, std::__type_descriptor_t), absent from libc++ before macOS 15.4 */
__attribute__((visibility("default"), weak))
void typed_delete(void *p, unsigned long long t) __asm("__ZdlPvSt19__type_descriptor_t");
void typed_delete(void *p, unsigned long long t) { (void)t; free(p); }
EOF

cp -R "$CX/lib64/apple_gptk/external" "$P/external"
cp -R "$CX/lib64/apple_gptk/wine" "$P/wine"
xattr -dr com.apple.quarantine "$P"

R="$P/external/D3DMetal.framework/Versions/A/Resources"
mv "$R/libdxccontainer.dylib" "$R/libdxccontainer_real.dylib"
install_name_tool -id "@loader_path/libdxccontainer_real.dylib" "$R/libdxccontainer_real.dylib"
codesign -f -s - "$R/libdxccontainer_real.dylib"
clang -arch x86_64 -dynamiclib -mmacosx-version-min=13.0 \
  -install_name "@rpath/libdxccontainer.dylib" -compatibility_version 1.0.0 -current_version 1.0.0 \
  -Wl,-reexport_library,"$R/libdxccontainer_real.dylib" -o "$R/libdxccontainer.dylib" "$P/shim.c"
codesign -f -s - "$R/libdxccontainer.dylib"

for e in "$CX"/*; do n=$(basename "$e"); [ "$n" = lib64 ] || ln -s "$e" "$P/cxroot/$n"; done
for e in "$CX"/lib64/*; do n=$(basename "$e"); [ "$n" = apple_gptk ] || ln -s "$e" "$P/cxroot/lib64/$n"; done
ln -s "$P/external" "$P/cxroot/lib64/apple_gptk/external"
ln -s "$P/wine" "$P/cxroot/lib64/apple_gptk/wine"
```

The folder path must not contain spaces. Wine's `--env` option, used below, splits on spaces.

## Using it

A game uses the patched copy when it's started with two environment variables, passed in one
`--env`:

- `CX_ROOT=$HOME/Library/d3dmetal-sonoma-shim/cxroot`: CrossOver then puts
  `$CX_ROOT/lib64/apple_gptk/wine` first on the DLL path, so the bridge modules load from the
  copy.
- `CX_APPLEGPTK_LIBD3DSHARED_PATH=$HOME/Library/d3dmetal-sonoma-shim/external/libd3dshared.dylib`:
  the path `libd3dshared` is loaded from.

Both are needed. The bridge modules find `libd3dshared` relative to their own location. With only
the second variable set, they load the app's original copy a second time, and the original
D3DMetal with it.

**The Elden Ring bridge's launcher** (`elden-ring/scripts/launch_er.sh`) adds both variables
whenever `~/Library/d3dmetal-sonoma-shim` exists, so there's nothing else to do.

Steam's Play button can't use the workaround: Steam starts the game without these variables. Use
the launcher.

## Things that don't work

- `DYLD_INSERT_LIBRARIES`: ignored. CrossOver's `bin/wine` is a Perl script, so macOS's System
  Integrity Protection strips `DYLD_*` variables, and `wineloader` has the hardened runtime without
  the dyld-environment entitlement.
- Setting only `CX_APPLEGPTK_LIBD3DSHARED_PATH`: see above.
- A shim folder under `~/Library/Application Support/...`: the space breaks `--env`.

## After a CrossOver update

The copies come from your CrossOver version. After updating CrossOver, run the build steps above
again.

If a newer D3DMetal needs more missing functions, find them by loading the framework from a small
x86_64 test program (`dlopen` + `dlerror`), and add each one to `shim.c`.

## Removing it

```zsh
rm -rf ~/Library/d3dmetal-sonoma-shim
```
