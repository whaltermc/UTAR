#include "script_patcher.h"

#include <cctype>
#include <cstring>

#include <algorithm>
#include <string>

namespace utarcore {

namespace {

} // namespace

std::vector<uint8_t> ScriptFixResult::toBlob() const {
    std::vector<uint8_t> out;
    auto u32 = [&](uint32_t value) {
        out.push_back(uint8_t(value & 0xFF));
        out.push_back(uint8_t((value >> 8) & 0xFF));
        out.push_back(uint8_t((value >> 16) & 0xFF));
        out.push_back(uint8_t((value >> 24) & 0xFF));
    };
    auto put_str = [&](const std::string &value) {
        u32(uint32_t(value.size()));
        out.insert(out.end(), value.begin(), value.end());
    };
    u32(uint32_t(fixes.size()));
    for (const ScriptFix &fix : fixes) {
        put_str(fix.rule);
        put_str(fix.original);
        put_str(fix.patched);
    }
    put_str(source);
    return out;
}

ScriptFixResult scriptFix(const std::string &source) {
    ScriptFixResult result;
    if (source.empty()) {
        return result;
    }

    std::string patched = source;

    // Fix _cache_visible_shaders viewport camera restoration
    const char *search = "func _cache_visible_shaders():\n"
        "\tvar camera = Camera3D.new()\n"
        "\tadd_child(camera)\n"
        "\n"
        "\tcamera.global_rotation_degrees = Vector3(-28, 28, 0)\n"
        "\tcamera.translate_object_local(Vector3.BACK * 500.0)\n"
        "\tcamera.current = true\n"
        "\n"
        "\tawait Utils.wait_frames(2)\n"
        "\tcamera.queue_free()\n";

    const char *replace = "func _cache_visible_shaders():\n"
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
        "\t\t\t\tnode.current = false\n"
        "\t\t\t\tnode.current = true\n"
        "\t\t\t\tbreak\n"
        "\t\t\tfor child in node.get_children():\n"
        "\t\t\t\tstack.push_back(child)\n";

    size_t pos = patched.find(search);
    if (pos != std::string::npos) {
        std::string original_block = patched.substr(pos, strlen(search));
        patched.replace(pos, strlen(search), replace);
        result.fixes.push_back({ "Scene._cache_visible_shaders camera restore", original_block, replace });
    }

    result.source = std::move(patched);
    return result;
}

} // namespace utarcore
