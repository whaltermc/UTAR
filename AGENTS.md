# AGENTS.md — UTAR (Until Then Android Runner)

## Overview
Android runner for the *Until Then* Godot 4.1.4 game pack. Kotlin + Compose UI, C++17 native core (`libutarcore`) via JNI, official Godot 4.1.4 Android template from Maven. Focus: fast startup of large PCKs (3.5 GB / ~211k entries) without re-indexing.

## Build
- Full: `./build.sh` (extracts FMOD SDK, fetches godot-cpp, builds natives, assembles debug APK)
- APK only: `./gradlew :app:assembleDebug`
- Tests: `./gradlew :app:test`
- ABIs: `ABIS="arm64-v8a x86_64" ./build.sh`
- NDK: 23.2.8568313 for natives, 29.0.14206865 for app core. JDK 17, SDK 34.
- Stale `app/libs/godot-lib*.aar` fails build on purpose; official template only.

## Structure
- `app/src/main/java/com/utar/runner/` — activities, UI (Compose), runtime helpers
- `app/src/main/java/com/utar/runner/core/Core.kt` — GameInstaller, PckPack, NativeCore JNI decls
- `app/src/main/cpp/` — native core: pck_engine, library_set, gdext_rewriter, script_patcher, settings_override, save_zip, jni_bridge
- `natives/` — GDExtension sources (threen, inkcpp, utinternal, fmod-for-godot, utap_shims)
- `engine/core/runtime/utar_runtime.h` — capability flags

## Conventions
- Kotlin official style; no comments unless asked.
- C++17, static libc++ (`-static-libstdc++`), links z log.
- Unit tests run against `app/src/test/resources/libutarcore.so` (bionic aarch64); rebuild native + copy .so before tests.
- No custom engine AAR; official Godot 4.1.4 only.
- Never commit secrets.

## Key flows
- Install: copy pack → parse index → scan APK natives → rewrite .gdextension configs → patch scripts → rewrite pack → extract fonts → align renderer.
- Launch: ensureStaged (size+mtime) → font extraction (stamp-keyed) → renderer alignment → start GameActivity in `:godot` process with `--main-pack`.

## PCK (libutarcore)
- `PckFile::open` tries sidecar `.meta` cache first (size+mtime keyed), else `parseIndex`; saves cache after parse.
- `findEntry` uses unordered_map (O(1)).
- Reads use `pread` at recorded offsets; no full load, no extraction.
- `rewrite` streams copy with replacements/additions; untouched payloads copied byte-for-byte.

## JNI
- Surface: `com.utar.runner.core.NativeCore` (static external methods).
- Handles are opaque `jlong` pointers (PckFile*). Always null-check.
- Progress callbacks: `onProgress(done,total)`.

## Testing
- `./gradlew :app:test` runs JUnit tests exercising native core via JNI.
- Must copy rebuilt .so to `app/src/test/resources/libutarcore.so`.

## Docs
- `README.md` — user-facing documentation.
- This file — AI-facing project context.
