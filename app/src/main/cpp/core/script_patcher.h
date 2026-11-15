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

namespace {

/** Exact source-code block replacement applied at install time. */
struct CodeBlockRule {
    const char *search;
    const char *replace;
    const char *reason;
    // When true, after matching [search], the replacement extends from [search]
    // through the matching close parenthesis (found via findMatchingCloseParen),
    // replacing the whole call expression with [replace].
    bool replace_through_close_paren = false;
};

// Godot 4.1.4's Viewport::_camera_3d_remove clears camera_3d without calling
// make_next_current, so Scene._cache_visible_shaders() stealing the viewport camera
// and then queue_free()ing it leaves the viewport with no camera. The chess scene
// (and every 3D scene) then renders black until another camera re-asserts itself —
// but scene_manager.start() (which does that via CamCloseUp) runs after _fade_in(),
// which hangs when Threen's tween never completes on Android. Save/restore the
// viewport camera so the viewport is never left without one.
constexpr CodeBlockRule CODE_BLOCK_RULES[] = {
    {
        "func _cache_visible_shaders():\n"
        "\tvar camera = Camera3D.new()\n"
        "\tadd_child(camera)\n"
        "\n"
        "\tcamera.global_rotation_degrees = Vector3(-28, 28, 0)\n"
        "\tcamera.translate_object_local(Vector3.BACK * 500.0)\n"
        "\tcamera.current = true\n"
        "\n"
        "\tawait Utils.wait_frames(2)\n"
        "\tcamera.queue_free()\n"
        "\t# Ensure viewport camera — InterpolatedCamera3D does not call make_current()\n"
        "\t# on enter_tree, so no camera may be set. Find one explicitly.\n"
        "\tvar vp = get_viewport()\n"
        "\tif vp.get_camera_3d() == null:\n"
        "\t\tvar stack = [get_tree().get_root()]\n"
        "\t\twhile stack.size() > 0:\n"
        "\t\t\tvar node = stack.pop_front()\n"
        "\t\t\tif node is Camera3D:\n"
        "\t\t\t\tnode.current = true\n"
        "\t\t\t\tbreak\n"
        "\t\t\tfor child in node.get_children():\n"
        "\t\t\t\tstack.push_back(child)\n"
	},
    {
        "@onready var tween = Threen.new()",
        "@onready var tween = null",
        "Fader.gd: do not instantiate Threen (tween never completes on Android)",
    },
    {
        "\tadd_child(tween)\n\tfocus.focus_name = \"Fader\"",
        "\tfocus.focus_name = \"Fader\"",
        "Fader.gd: do not add null tween to scene tree",
    },
    {
        "func _fade(into: bool, duration: float, delay: float):\n"
        "\tvar modulate = Color(0, 0, 0, 0) if into else Color.BLACK\n"
        "\n"
        "\tif delay > 0:\n"
        "\t\tawait Utils.wait(delay)\n"
        "\n"
        "\ttween.interpolate_property(fader, \"modulate\", fader.modulate, modulate, duration)\n"
        "\ttween.start()\n"
        "\tawait tween.tween_completed",
        "func _fade(into: bool, duration: float, delay: float):\n"
        "\tvar modulate = Color(0, 0, 0, 0) if into else Color.BLACK\n"
        "\n"
        "\tif delay > 0:\n"
        "\t\tawait Utils.wait(delay)\n"
        "\n"
        "\tvar _t = create_tween()\n"
        "\t_t.tween_property(fader, \"modulate\", modulate, duration)\n"
        "\t_t.start()\n"
        "\tawait _t.tween_completed",
        "Fader.gd: use Godot built-in Tween instead of Threen",
    },
};

} // namespace

/** Finds and fixes known GDScript API mismatches inside game scripts at install time:
 * a call taking more arguments than the rule allows is trimmed back to the documented
 * maximum (respecting nested (), [], {} and string literals), calls that already fit are
 * left byte-for-byte untouched, and every applied fix is reported.
 */
ScriptFixResult scriptFix(const std::string &source);

} // namespace utarcore

#endif // UTARCORE_SCRIPT_PATCHER_H