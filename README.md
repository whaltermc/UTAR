# UTAR — Until Then · Android Runner

An Android app that runs the *Until Then* Godot 4.1.4 game pack with the Godot Android runtime,
installs the pack into app-private storage for fast loading, and swaps the game's desktop-only
GDExtensions for Android builds.

## Layout

```
app/      Android app: home UI, PCK install + patch, Godot game activity
natives/  sources for the game's GDExtensions (build output is not committed)
runtime/  FMOD Studio SDK (local only, not committed)
logs/     exported session/crash logs (not committed)
```

## Requirements

- JDK 17, Android SDK 34
- Godot Android library 4.1.4 — `app/build.gradle.kts` pulls `org.godotengine:godot:4.1.4.stable`
  from Maven Central. A local `app/libs/godot-lib.4.1.4.stable.template_release.aar` takes priority.
- Godot 4.1.4 `godot-cpp` and the FMOD Studio API 2.02.14 **Android** package to build the natives.

## Build

```bash
./build.sh
```

That extracts the FMOD SDK, fetches godot-cpp, builds the native extensions for `arm64-v8a`, then
runs `:app:assembleDebug`. Set `ABIS="arm64-v8a x86_64"` to add another ABI, or pass another
Gradle task (e.g. `./build.sh :app:assembleRelease`). To only build the APK from already-built
natives:

```bash
./gradlew :app:assembleDebug
```

The APK targets `arm64-v8a` only (see `abiFilters`); add other ABIs via `ABIS` on `build.sh`
together with matching extension builds.

## Extensions in `natives/`

| Source | Output library | Entry symbol |
| --- | --- | --- |
| `threen/` | `libthreen.so` | `threen_library_init` |
| `inkcpp/` (bridge + inkcpp runtime) | `libinkcpp.so` | `inkcpp_library_init`, `utink_library_init` |
| `utinternal/` | `libinternal.so` | `internal_library_init`, `uinternal_library_init` |
| `fmod-for-godot/` (upstream addon + UTAP patch) | `libfmod_extension.so` | `fmod_library_init` |
| `utap_shims/` | GDScript (`json_utils.gd`) | replaces the proprietary `jsonutils` extension |

The FMOD extension follows the UTAP method: the vendored `fmod-for-godot` source (upstream addon
with `fmod-for-godot.patch` applied: emitter volume/paused/playing bindings, real `FMODSettings`)
is the compile source, and `fmod_scene_register.cpp` inside it registers only the runtime scene
types and exports `fmod_library_init` — the addon's own `register_types.cpp` targets the pre-4.1
GDExtension API and is dropped. The FMOD Java bridge (`app/libs/fmod.jar`, `org.fmod.FMOD.init`
in `GameActivity`) runs before the engine starts.

### Building them

`natives/SConstruct` builds all four libraries in one pass. It finds its inputs by itself:

- **godot-cpp** (Godot 4.1.4) at `natives/godot-cpp`, or whatever
  `GODOT_CPP_PATH` / `scons godot_cpp=...` points at
- **FMOD Studio API 2.02.14 for Android** at `runtime/fmodstudioapi20214android/api`, or
  whatever `FMOD_SDK_ROOT` / `scons fmod_sdk=...` points at
- **Android NDK 23.2.8568313** (the revision Godot 4.1 uses), installed through the SDK;
  `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) must be set, godot-cpp reads it for its toolchain

The ABI library directory is derived from `arch` (`arm64` → `arm64-v8a`, `x86_64` → `x86_64`),
so the same checkout builds every ABI and FMOD's `core/lib/<abi>` + `studio/lib/<abi>` are used.
FMOD is linked in and libc++ is static-linked, so the extensions do not depend on the template's
`libc++_shared.so`.

```bash
cd natives
scons platform=android target=template_release arch=arm64
```

Objects go to `natives/build/<platform>/<target>/<arch>/<lib>/`, keeping arm64 and x86_64 builds
apart, and each library is installed to `natives/bin/` **and** to `natives/android/<abi>/`, which
Gradle packages as jniLibs next to `libgodot_android.so`. Both directories are outside the
extension source folders.

An arm64 `template_release` build yields `libthreen.so`, `libinkcpp.so`, `libinternal.so` and
`libfmod_extension.so` (all AArch64, libc++ static-linked). `./build.sh` runs the whole pipeline:
it extracts the FMOD SDK, fetches godot-cpp, builds every ABI and assembles the APK.

If the FMOD SDK is missing, `libfmod_extension` is skipped with a warning and the other three
libraries still build. The FMOD sources include `<fmod.hpp>`, `<fmod_studio.hpp>` and
`<fmod_errors.h>`, resolved from the SDK's `core/inc` and `studio/inc`.

Expected SDK layout:

```
runtime/fmodstudioapi20214android/api/
  core/inc/fmod.hpp
  core/lib/arm64-v8a/libfmod.so
  studio/inc/fmod_studio.hpp
  studio/lib/arm64-v8a/libfmodstudio.so
```

## How the desktop extensions get replaced

The game was exported with desktop binaries (`*.windows.x86_64.dll`, `*.linux.x86_64.so`), which
cannot load on Android. On install the app rebuilds every `.gdextension` in the pack:

1. read the config's `entry_symbol`;
2. pick the packaged native library whose ELF dynamic symbol table exports that symbol;
3. fall back to matching library names (`libfoo.windows.x86_64.dll` → `libfoo…so`) when no
   symbol match exists;
4. replace any stale `android.*` lines with an `android.arm64` entry pointing at
   `res://utar_natives/<abi>/<lib>.so`.

That path is intentionally absent from the pack, so Godot 4.1's Android loader falls back to the
bare library name and resolves it from the APK's native library directory. The installed libraries
are read straight out of the APK (`lib/<abi>/`), because current AGP builds set
`extractNativeLibs=false` and never populate `nativeLibraryDir` on disk.

`jsonutils` has no Android build, so `Store.gd`'s `JsonUtils.new()` is rewritten to load the
GDScript shim at `natives/utap_shims/json_utils.gd`, which is appended to the installed pack as
`res://utar_natives/json_utils.gd` (packaged as an APK asset). This keeps the store database
diff/patch working without the proprietary extension.

## Runtime

`GameActivity` extends `FullScreenGodotApp` and returns `--main-pack <installed pack>` plus the
selected backend flags from `getCommandLine()`. It runs in the `:godot` process, so engine
startup/indexing and its native heap are isolated from the runner UI: an ANR or OOM in the game
process no longer takes the launcher down with it.

Both activities are locked to `sensorLandscape` and run edge-to-edge with the system bars hidden
(`ImmersiveMode`); the bars re-hide whenever the window regains focus.

| Backend | Flags |
| --- | --- |
| Auto | none (game's export setting) |
| Vulkan | `--rendering-method mobile --rendering-driver vulkan` |
| GLES | `--rendering-method gl_compatibility --rendering-driver opengl3` |

## Logs

`Export latest log` writes one text file with the runner session log, logcat output for both the
launcher and the `:godot` process (its pid is recorded in `files/logs/game.pid`), and the game's
`user://logs/godot.log` when the export produced one.
