#ifndef UTINTERNAL_PATCH_LOADER_H
#define UTINTERNAL_PATCH_LOADER_H

namespace utinternal {

/**
 * Runs at GDExtension scene-level initialization, before any game resource loads.
 *
 * When the runner selected the Compatibility (GLES3) backend, this scans the mounted game
 * pack for `.gdshader` sources, rewrites the ones that cannot compile under the Godot 4.1
 * compatibility renderer, caches the decisions by shader MD5 + engine + rules version, writes
 * the GLES3 overrides into a small patch pack and mounts it with patch priority
 * (`ProjectSettings.load_resource_pack(..., replace_files=true)`). The original pack — and
 * therefore every Vulkan shader — is never modified, and with Forward+/Vulkan selected nothing
 * is mounted at all.
 */
void load_shader_patch_pack();

} // namespace utinternal

#endif // UTINTERNAL_PATCH_LOADER_H
