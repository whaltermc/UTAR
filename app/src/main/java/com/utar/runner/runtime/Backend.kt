package com.utar.runner.runtime

/**
 * Rendering backend the Godot runtime should start with.
 *
 * Selection is manual only: the runner never picks or switches a renderer on its own, and the
 * game pack's renderer setting is aligned to the chosen backend at every launch
 * ([com.utar.runner.core.ProjectSettingsOverride]). Vulkan selection always runs the game's
 * original Forward+/Mobile shaders; Compatibility selection additionally mounts the generated
 * GLES3 shader overrides (see [com.utar.runner.core.ShaderPatcher]).
 */
enum class Backend(
    val label: String,
    val summary: String,
    val engineArgs: List<String>,
) {
    FORWARD_PLUS(
        label = "Forward+ (Vulkan)",
        summary = "Vulkan Forward+/Mobile renderer with the game's original shaders.",
        engineArgs = listOf(
            "--rendering-method", "mobile", "--rendering-driver", "vulkan",
            "--use_immersive",
        ),
    ),
    COMPATIBILITY(
        label = "Compatibility (GLES3)",
        summary = "OpenGL ES 3 compatibility renderer with automatically generated GLES3 shader overrides.",
        engineArgs = listOf(
            "--rendering-method", "gl_compatibility", "--rendering-driver", "opengl3",
            "--use_immersive",
        ),
    );

    /** Marker value written next to the patch pack and read by the native patch loader. */
    val nativeMarker: String get() = name.lowercase()

    companion object {
        fun fromName(name: String?): Backend {
            // Manual selection only: unknown/legacy names resolve to Forward+, never to a
            // probed or automatic choice.
            return when (name) {
                "COMPATIBILITY", "GLES" -> COMPATIBILITY
                else -> FORWARD_PLUS // FORWARD_PLUS, VULKAN (legacy), AUTO (legacy), anything else
            }
        }
    }
}
