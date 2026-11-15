#ifndef UTARCORE_SCRIPT_PATCHER_H
#define UTARCORE_SCRIPT_PATCHER_H

#include <cstdint>
#include <string>
#include <vector>

namespace utarcore {

struct ScriptFix {
    std::string rule;
    std::string original;
    std::string patched;
};

struct ScriptFixResult {
    std::string source;
    std::vector<ScriptFix> fixes;

    /** Serialises for the Kotlin side: u32 fixCount; per fix [len rule, len original, len patched]; [len text, text]. */
    std::vector<uint8_t> toBlob() const;
};

/** Finds and fixes the _cache_visible_shaders viewport camera issue in Scene.gd:
 * Godot 4.1.4's Viewport::_camera_3d_remove clears camera_3d without calling
 * make_next_current. Scene._cache_visible_shaders() steals the viewport camera
 * and then frees it, leaving the viewport with no camera → black screen.
 * Fix: after freeing temp camera, search the scene tree for any Camera3D and
 * set it as current (false then true to force the setter).
 */
ScriptFixResult scriptFix(const std::string &source);

} // namespace utarcore

#endif // UTARCORE_SCRIPT_PATCHER_H
