package com.utar.runner.runtime

/**
 * Rendering backend the Godot runtime should start with.
 *
 * Selection is manual only: the runner never picks or switches a renderer on its own.
 * Both backends run the game's original shaders without modification.
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
            "--rendering-method", "forward_plus", "--rendering-driver", "vulkan",
            "--use_immersive",
            "--verbose",
        ),
    ),
    COMPATIBILITY(
        label = "Compatibility (GLES3)",
        summary = "OpenGL ES 3 compatibility renderer with the game's original shaders.",
        engineArgs = listOf(
            "--rendering-method", "gl_compatibility", "--rendering-driver", "opengl3",
            "--use_immersive",
            "--verbose",
        ),
    );


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
