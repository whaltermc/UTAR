# FMOD audio on Android

The game ships its audio as FMOD Studio banks and talks to them through the vendored
fmod-for-godot GDExtension (MIT, © Alessandro Famà, source under `natives/fmod-for-godot`). FMOD has no official Godot Android build, so the runner builds
the addon itself against the **FMOD Studio API 2.02.14 for Android** and ships the result next
to FMOD's own runtime libraries.

This page documents every piece the runner has to get right, and the API surface the game's
GDScript actually sees.

## 1. What ends up in the APK

| File | Role | Comes from |
| --- | --- | --- |
| `lib/<abi>/libfmod.so` | FMOD Core (low-level mixer, codecs, output) | FMOD SDK `api/core/lib/<abi>/` |
| `lib/<abi>/libfmodstudio.so` | FMOD Studio runtime (events, banks, parameters) | FMOD SDK `api/studio/lib/<abi>/` |
| `lib/<abi>/libfmod_extension.so` | The Godot GDExtension, entry symbol `fmod_library_init` | built by `natives/SConstruct` |
| `classes.dex` (`org.fmod.*`) | Java bridge: audio device, AAudio/OpenSL ES flags, headset receiver | `app/libs/fmod.jar` |

`natives/android/<abi>/` is wired in as a `jniLibs` source dir, so everything under it is packaged
as `lib/<abi>/…`. `app/libs/fmod.jar` is a normal Gradle file dependency and is dexed into the
APK.

The committed copies under `natives/android/arm64-v8a/` mean a build works without the SDK
tarball; `build.sh` only refreshes them when the SDK is present.

## 2. Startup order (why it matters)

FMOD's Android backend needs a `Context` to open an audio device before any Studio call.
`GameActivity` therefore does this **before** `super.onCreate()`:

```kotlin
System.loadLibrary("fmod")        // FMOD Core
System.loadLibrary("fmodstudio")  // FMOD Studio
org.fmod.FMOD.init(this)          // Java bridge: binds the Context, caches device flags
```

Only then does `super.onCreate()` run Godot's setup, which mounts the pack, reads
`res://addons/fmod/fmod.gdextension` and calls `fmod_library_init` — the extension's
`FMODStudioModule` singleton is created at `MODULE_INITIALIZATION_LEVEL_SCENE`.

On exit, `onDestroy()` calls `org.fmod.FMOD.close()` (the Java bridge refuses re-init later if
this is skipped).

Consequences worth knowing:

- Loading the native libraries without `FMOD.init()` leaves FMOD with no output device: banks
  load, events "start", nothing is audible.
- `System.loadLibrary` must succeed for the device ABI; a missing `libfmod.so` throws
  `UnsatisfiedLinkError`. The runner catches that, logs
  `FMOD bridge unavailable (…)` to `session.log`, and starts the game without audio rather than
  crashing before the engine exists.
- A missing `fmod.jar` fails the same way (`NoClassDefFoundError: org.fmod.FMOD`).
- `libfmod_extension.so` is discovered by the GDExtension rewriter through its entry symbol, not
  by file name, and `libfmod.so`/`libfmodstudio.so` are deliberately excluded from extension
  matching (`library_set.cpp`, `ENGINE_LIBRARIES`).

## 3. GDScript API surface

### Singleton

`FMODStudioModule` is registered as an engine singleton, so GDScript reaches it by name:

| Call | Returns |
| --- | --- |
| `FMODStudioModule.init()` / `FMODStudioModule.shutdown()` | `bool` |
| `FMODStudioModule.get_studio_system()` | `StudioSystem` |

The module also exposes FMOD's constants (`FMOD_STUDIO_LOAD_BANK_NORMAL`, `_NONBLOCKING`,
`_DECOMPRESS_SAMPLES`, `_UNENCRYPTED`, the `FMOD_STUDIO_INIT_*` flags and the Studio enums) for
use from scripts.

All other classes are registered in `ClassDB`, so they can be referenced by name
(`StudioSystem`, `EventInstance`, …) and are returned as `RefCounted`.

### `StudioSystem`

| Group | Methods |
| --- | --- |
| Lifetime | `is_valid()`, `update()`, `flush_commands()`, `flush_sample_loading()` |
| Lookup | `get_event(path)`, `get_bus(path)`, `get_vca(path)`, `get_bank(path)`, `get_event_by_id(guid)`, `get_bus_by_id`, `get_vca_by_id`, `get_bank_by_id`, `lookup_id(path)`, `lookup_path(guid)` |
| Banks | `load_bank_file(bank_path, load_bank_flags, load_sample_data)`, `unload_all()`, `get_bank_count()`, `get_bank_list(capacity)` |
| Global parameters | `get_parameter_by_name`, `set_parameter_by_name(name, value, ignore_seek_speed)`, `set_parameter_by_name_with_label`, `get_parameter_by_id`, `set_parameter_by_id`, `set_parameter_by_id_with_label`, `set_parameters_by_ids`, `get_parameter_description_by_name`, `get_parameter_description_by_id`, `get_parameter_description_count`, `get_parameter_description_list`, `get_parameter_label_by_name`, `get_parameter_label_by_id` |
| Listeners | `get_num_listeners`, `set_num_listeners`, `get_listener_attributes`, `set_listener_attributes(listener, attributes, attenuation_position)`, `get_listener_weight`, `set_listener_weight` |
| Diagnostics | `get_cpu_usage(studio, core)`, `get_memory_usage(usage)`, `get_buffer_usage()`, `reset_buffer_usage()` |
| Capture | `start_command_capture(file, flags)`, `stop_command_capture()`, `load_command_replay(file, flags)`, `set_callback(callable, mask)` |

### `EventDescription`

`get_id()`, `get_path()`, `get_length()`, `is_oneshot()`, `is_stream()`, `is_3d()`,
`is_snapshot()`, `is_doppler_enabled()`, `has_sustain_point()`, `get_sound_size()`,
`get_min_max_distance()`, `create_instance()`, `get_instance_count()`, `get_instance_list(capacity)`,
`load_sample_data()`, `unload_sample_data()`, `get_sample_loading_state()`,
`release_all_instances()`, `set_callback(callable, mask)`, plus parameter description/label and
user-property accessors.

### `EventInstance`

`start()`, `stop(stop_mode)`, `release()`, `key_off()`, `get_paused()`, `set_paused(paused)`,
`get_playback_state()`, `is_virtual()`, `get_description()`, `get_timeline_position()`,
`set_timeline_position(position)`, `get_reverb_level(i)` / `set_reverb_level(i, level)`,
`get_listener_mask()` / `set_listener_mask(mask)`, `get_3d_attributes(attributes)`,
`set_3d_attributes(attributes)`, `get_property(type)` / `set_property(type, value)`,
`set_parameter_by_name(name, value, ignore_seek_speed)`, `set_parameter_by_name_with_label`,
`get_parameter_by_name`, the `_by_id` equivalents, `set_parameters_by_ids`,
`get_cpu_usage(dict)`, `get_memory_usage(usage)`, `set_callback(callable, mask)`.

Volume and pitch on an instance mirror FMOD's output structs: `set_volume(float)` is bound as a
setter, while `get_volume()` / `get_pitch()` return a `Dictionary`. Buses take a plain `float`.

### `Bus`

`get_id()`, `get_path()`, `get_volume()` / `set_volume(float)`, `get_paused()` / `set_paused`,
`get_mute()` / `set_mute`, `stop_all_events(stop_mode)`, `lock_channel_group()`,
`unlock_channel_group()`, `get_cpu_usage(dict)`, `get_memory_usage(usage)`.

### `Bank` and `VCA`

`Bank`: `get_id()`, `get_path()`, `unload()`, `load_sample_data()`, `unload_sample_data()`,
`get_loading_state()`, `get_sample_loading_state()`, `get_event_count()`, `get_event_list(capacity)`,
`get_bus_count()`, `get_bus_list(capacity)`, `get_vca_count()`, `get_vca_list(capacity)`.
`VCA`: `get_id()`, `get_path()`, `get_volume()`, `set_volume(float)`.

### `CommandReplay`

`get_length()`, `get_command_count()`, `get_command_info(i)`, `get_command_string(i, length)`,
`get_command_at_time(time)`, `set_bank_path(path)`, `start()`, `stop()`, `seek_to_time(time)`,
`seek_to_command(i)`, `get_paused()` / `set_paused()`, `get_playback_state()`,
`get_current_command()`, `release()`.

### Resources and value types

| Class | Notes |
| --- | --- |
| `FMODAsset` | `path`, `guid`, `name` |
| `BankAsset` | `FMODAsset` + `file_path`, `modified_time` |
| `EventAsset`, `BusAsset`, `VCAAsset`, `ParameterAsset` | assets the nodes reference |
| `FMOD_STUDIO_PARAMETER_ID` | `data1`, `data2` |
| `FMOD_STUDIO_PARAMETER_DESCRIPTION` | `name`, `id`, `minimum`, `maximum`, `default_value`, `type`, `flags`, `guid` |
| `FMOD_STUDIO_USER_PROPERTY` | `name`, `type`, and typed `*value` fields |
| `FMOD_3D_ATTRIBUTES` | `position`, `velocity`, `forward`, `up` |
| `FMOD_STUDIO_BUFFER_INFO` / `_BUFFER_USAGE` | command queue and handle usage |
| `FMOD_STUDIO_CPU_USAGE`, `FMOD_CPU_USAGE`, `FMOD_STUDIO_MEMORY_USAGE` | diagnostics |

Enums re-exported by `FMODStudioModule`: `FMOD_STUDIO_LOADING_STATE`, `FMOD_STUDIO_LOAD_MEMORY_MODE`,
`FMOD_STUDIO_PARAMETER_TYPE`, `FMOD_STUDIO_USER_PROPERTY_TYPE`, `FMOD_STUDIO_EVENT_PROPERTY`,
`FMOD_STUDIO_PLAYBACK_STATE`, `FMOD_STUDIO_STOP_MODE`, `FMOD_STUDIO_INSTANCETYPE`.

### Scene nodes

| Node | Key members |
| --- | --- |
| `StudioBankLoader` | `banks` (array of `BankAsset`), `load_on` / `unload_on` (`GameEvent`), `preload_sample_data`, `num_banks`, `load()`, `unload()`, `handle_game_event(event)` |
| `StudioEventEmitter3D` / `StudioEventEmitter2D` | `event` (`EventAsset`), `play()`, `stop()`, `lookup()`, `is_playing()`, `set_playing(bool)`, `paused`, `volume`, `trigger_once`, `allow_fadeout`, `preload_samples`, `play_event` / `stop_event`, `overridden_parameters`, `rigidbody`, `get_max_distance()` |
| `StudioListener3D` / `StudioListener2D` | listener transforms feeding FMOD's 3D attributes |
| `StudioParameterTrigger`, `StudioGlobalParameterTrigger` | set parameters when a game event fires |
| `FMODDebugMonitor` | performance monitors |

`GameEvent` (from `RuntimeUtils`) is the trigger enum used by `load_on`, `unload_on`,
`play_event` and `stop_event`: `GAMEEVENT_NONE`, `GAMEEVENT_ENTER_TREE`, `GAMEEVENT_READY`,
`GAMEEVENT_EXIT_TREE`.

## 4. Practical examples

Banks are opened through the addon's `FMODGodotBlockingIO`, which routes every file to Godot's
`FileAccess`. That means `res://` paths inside the PCK work without extraction, and `user://`
paths work for anything written at runtime.

```gdscript
# Load a bank and play an event directly.
var studio := FMODStudioModule.get_studio_system()
studio.load_bank_file("res://audio/Master.bank", FMODStudioModule.FMOD_STUDIO_LOAD_BANK_NORMAL, true)
var description := studio.get_event("event:/Music/MainMenu")
var instance := description.create_instance()
instance.set_volume(0.7)
instance.start()
```

```gdscript
# Or via the scene node (loads on _ready, stops on exit).
var loader := StudioBankLoader.new()
loader.banks = [preload("res://audio/Master.bank")]
loader.load_on = RuntimeUtils.GAMEEVENT_READY
loader.preload_sample_data = true
add_child(loader)
```

Guidelines:

- Always keep `Master.bank`, `Master.strings.bank` and the event bank together; an event path
  resolves to nothing if its bank was not loaded.
- This build initialises Studio with `FMOD_STUDIO_INIT_NORMAL` and does **not** run a per-frame
  update itself — something in the game loop has to call `update()`, which is what the game's own
  audio code does. If you script FMOD yourself, call
  `FMODStudioModule.get_studio_system().update()` once per frame or nothing advances.
- `set_parameter_by_name` / `set_parameter_by_name_with_label` are the safe way to drive
  parameters from gameplay; `ignore_seek_speed = false` is the usual choice.
- Keep the SDK version at **2.02.14**: the extension is compiled against those headers and the
  game's banks were built by the matching Studio version.

## 5. Building the extension

```bash
# 1. Drop the Android package (FMOD account required) so build.sh can unpack it:
#    ~/Downloads/fmodstudioapi20214android.tar.gz
# 2. Build everything:
./build.sh
```

`natives/SConstruct` looks for the SDK, in order, at:

1. `$FMOD_SDK_ROOT`
2. `scons … fmod_sdk=/path/to/api`
3. `runtime/fmodstudioapi20214android/api`

`template_debug` builds define `FMOD_DEBUG`, which turns on FMOD's internal logging and the
extension's debug monitors. Android builds define `FMOD_ANDROID`. Outputs are copied to
`natives/android/<abi>/`, which is what the APK packages.

If the SDK is missing, `build.sh` warns and keeps the committed libraries and extension; only
`libfmod_extension.so` stops tracking upstream source changes.

## 6. Troubleshooting

| Symptom | Check |
| --- | --- |
| `UnsatisfiedLinkError: libfmod.so` / `libfmodstudio.so` | Libraries missing for the device ABI under `natives/android/<abi>/`; `abiFilters` is `arm64-v8a` only. |
| `NoClassDefFoundError: org.fmod.FMOD` | `app/libs/fmod.jar` missing from the APK. |
| `FMOD bridge unavailable` in `session.log` | The two `System.loadLibrary` calls or `FMOD.init()` failed; the game is running without audio on purpose. |
| `GDExtension … no Android build matched` in `pck-patch.log` | `res://addons/fmod/fmod.gdextension` has no `fmod_library_init` library to bind; rebuild `libfmod_extension.so`. |
| Silence, no errors | Bank not loaded, wrong event path/guid, or `Setup`/`Master` banks missing from the pack. |
| `FMOD_ERR_FILE_NOTFOUND` on bank load | The `.bank` is not inside the installed PCK (or `user://`) — FMOD I/O goes through Godot's `FileAccess`. |
| Output device switches (headphones, Bluetooth) break audio | `org.fmod.FMOD` installs a headset receiver; `FMOD.init()` must run on every process start (it does, in `GameActivity`). |
| Crackling / dropouts | Try the `get_buffer_usage()` / `get_cpu_usage()` diagnostics, then raise FMOD's buffer via the loader's `preload_sample_data` or reduce simultaneous 3D emitters. |

## 7. Licensing

FMOD Studio is proprietary — the SDK (`runtime/`) is gitignored and never committed. The vendored
fmod-for-godot addon is MIT; see `natives/fmod-for-godot/LICENSE`. Shipping an app that uses FMOD
still requires the FMOD commercial licence for your distribution.
