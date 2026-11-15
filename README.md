# UTAR — Until Then · Android Runner

Android runner for the *Until Then* Godot 4.1.4 game pack. Ships the **official** Godot
4.1.4 Android template (from Maven Central), installs the pack into app-private storage,
rewrites desktop-only GDExtensions for Android, and exposes a native C++ core
(`libutarcore`) for fast PCK I/O. Optimised for instant startup of large packs
(3.5 GB / ~211 k entries) without re-indexing.

## Layout

```
app/      Android app: Compose UI, PCK install/patch, Godot game activity, JNI core
natives/  sources for the game's GDExtensions (build output is not committed)
engine/   runtime capability header shared by launcher/JNI
runtime/  FMOD Studio SDK (local only, not committed)
logs/     exported session/crash logs (not committed)
```

## Requirements

- JDK 17, Android SDK 34, NDK 23.2.8568313 (natives) / 29.0.14206865 (app core)
- Godot Android library 4.1.4 — `org.godotengine:godot:4.1.4.stable` from Maven Central (official, never a custom AAR)
- Godot 4.1.4 `godot-cpp` and FMOD Studio API 2.02.14 Android package to build the natives

## Build

```bash
./build.sh
```

Extracts the FMOD SDK, fetches godot-cpp, builds the native extensions for `arm64-v8a`,
then runs `:app:assembleDebug`. Set `ABIS="arm64-v8a x86_64"` to add another ABI, or pass
another Gradle task (e.g. `./build.sh :app:assembleRelease`). To build only the APK:

```bash
./gradlew :app:assembleDebug
```

A stale `app/libs/godot-lib*.aar` makes `build.sh` fail on purpose — delete it; the build
always uses the official 4.1.4 template.

## Native core (`libutarcore`)

All heavy I/O and byte-precision work lives in `app/src/main/cpp/` (C++17, static libc++):

| File | Responsibility |
| --- | --- |
| `pck_engine.cpp` | Godot 4.1 PCK v2 reader/writer: header + directory parsing, sidecar index cache (`.meta`), `pread`-based entry reads, streaming rewrite, small override packs |
| `library_set.cpp` | APK ELF64 scanning + symbol extraction for GDExtension matching |
| `gdext_rewriter.cpp` | `.gdextension` config rewriting (desktop → `res://utar_natives/<abi>/`) |
| `save_zip.cpp` | `user://` save-data zip backup/restore |
| `jni_bridge.cpp` | JNI surface (`com.utar.runner.core.NativeCore`) |

### Fast startup / no re-indexing

- The staged pack is validated by size + mtime only (`GameInstaller.ensureStaged`); no hashing, no copy on a matching stage.
- The parsed PCK directory is cached in a sidecar `game.pck.meta` keyed by size+mtime, so re-opens (font pass, script pass, engine mount) skip directory parsing; entries are stored with absolute offsets.
- `PckFile::findEntry` uses an `unordered_map` (O(1)) instead of a linear scan, critical for 211 k-entry packs.
- Entry reads use `pread()` at recorded offsets; payloads are never copied into RAM wholesale and the pack is never extracted into loose files.
- Full page-cache warmup was deliberately removed: reading 3+ GB before the engine starts only delays the first frame; the compact index is loaded on demand.
- Fonts are extracted once per pack (stamp-keyed) and reused.

### Splitting the pack

The runner keeps a single main pack (`--main-pack`) for full Godot compatibility. Optional
small override packs can be produced with `PckFile::writeNew` (used for renderer alignment
and patches) instead of rewriting the whole archive when only a handful of files change.

### Benchmarking

Timing is captured at:
- `PckFile::open` (cache hit vs. full parse) → native log
- header parse / directory parse / entry registration (within `parseIndex`)
- Godot setup (pack indexed) in `GameActivity` → `SessionLog`/`DebugLog`
- first frame drawn → `DebugLog.timing`
- font extraction / renderer alignment in `HomeViewModel`

Profile with both small packs and the 3.5 GB / 211 k-entry pack; compare cache-hit vs.
cold-parse open times. No custom virtual filesystem or asset preloading is introduced.

## Extensions in `natives/`

| Source | Output library | Entry symbol |
| --- | --- | --- |
| `threen/` | `libthreen.so` | `threen_library_init` |
| `inkcpp/` | `libinkcpp.so` | `inkcpp_library_init`, `utink_library_init` |
| `utinternal/` | `libinternal.so` | `internal_library_init`, `uinternal_library_init` |
| `fmod-for-godot/` | `libfmod_extension.so` | `fmod_library_init` |
| `utap_shims/` | GDScript (`json_utils.gd`) | replaces proprietary `jsonutils` |

See the in-source comments in `natives/SConstruct` for build details.

## Runtime

`GameActivity` extends `FullScreenGodotApp` and returns `--main-pack <installed pack>` plus
backend flags from `getCommandLine()`. It runs in the `:godot` process, isolating engine
startup and its native heap from the launcher UI.

| Backend | Flags |
| --- | --- |
| Auto | none |
| Vulkan | `--rendering-method mobile --rendering-driver vulkan` |
| GLES | `--rendering-method gl_compatibility --rendering-driver opengl3` |

## Logs

`Export latest log` writes one text file: runner session log, logcat for launcher + `:godot`
process (pid in `files/logs/game.pid`), and `user://logs/godot.log` when present.
