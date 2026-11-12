# UTAR — Until Then · Android Runner

An Android app that runs the *Until Then* Godot 4.1.4 game pack with the Godot Android runtime,
installs the pack into app-private storage for fast loading, and swaps the game's desktop-only
GDExtensions for Android builds.

## Layout

```
app/      Android app: home UI, PCK install + patch, Godot game activity
natives/  sources for the game's GDExtensions (build output is not committed)
```

## Requirements

- JDK 17, Android SDK 34
- Godot Android library 4.1.4 — `app/build.gradle.kts` pulls `org.godotengine:godot:4.1.4.stable`
  from Maven Central. A local `app/libs/godot-lib.4.1.4.stable.template_release.aar` takes priority.
- Godot 4.1.4 `godot-cpp` and the FMOD Studio API 2.02.14 **Android** package to build the natives.

## Build

```bash
./gradlew :app:assembleDebug
```

The APK targets `arm64-v8a` and `x86_64` (see `abiFilters`). Add `armeabi-v7a` there if you need
32-bit devices, together with matching extension builds.

## Extensions in `natives/`

| Source | Output library | Entry symbol |
| --- | --- | --- |
| `threen/` | `libthreen.so` | `threen_library_init` |
| `inkcpp/` (bridge + inkcpp runtime) | `libinkcpp.so` | `inkcpp_library_init`, `utink_library_init` |
| `utinternal/` | `libinternal.so` | `internal_library_init`, `uinternal_library_init` |
| `fmod/` | `libfmod_extension.so` | `fmod_library_init` |
| `utap_shims/` | GDScript (`json_utils.gd`) | replaces the proprietary `jsonutils` extension |

### Building them

`threen/SConstruct` builds all four libraries in one pass. It finds its inputs by itself:

- **godot-cpp** (Godot 4.1.4) at `natives/godot-cpp`, `natives/threen/godot-cpp`, or whatever
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
cd natives/threen
scons platform=android target=template_release arch=arm64
```

Objects go to `build/<platform>/<target>/<arch>/<lib>/`, keeping arm64 and x86_64 builds apart,
and each library is installed to `bin/` **and** to `natives/android/<abi>/`, which Gradle
packages as jniLibs next to `libgodot_android.so`.

An arm64 `template_release` build currently yields `libthreen.so`, `libinkcpp.so` and
`libinternal.so` (all AArch64, libc++ static-linked); `libfmod_extension.so` is skipped until
the FMOD SDK is in place.

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
4. replace any stale `android.*` lines with `android.arm64` / `android.x86_64` entries pointing at
   `res://utar_natives/<abi>/<lib>.so`.

That path is intentionally absent from the pack, so Godot 4.1's Android loader falls back to the
bare library name and resolves it from the APK's native library directory.

## Runtime

`GameActivity` extends `FullScreenGodotApp` and returns `--main-pack <installed pack>` plus the
selected backend flags from `getCommandLine()`:

| Backend | Flags |
| --- | --- |
| Auto | none (game's export setting) |
| Vulkan | `--rendering-method mobile --rendering-driver vulkan` |
| GLES | `--rendering-method gl_compatibility --rendering-driver opengl3` |

## Logs

`Export latest log` writes one text file with the runner session log, the engine's logcat output
for this process, and the game's `user://logs/godot.log` when the export produced one.
