package com.utar.runner.runtime

/** Rendering backend the Godot runtime should start with. */
enum class Backend(
    val label: String,
    val summary: String,
    val engineArgs: List<String>,
) {
    AUTO(
        label = "Auto",
        summary = "Keep whatever renderer the game was exported with.",
        engineArgs = emptyList(),
    ),
    VULKAN(
        label = "Vulkan",
        summary = "Force the mobile renderer on Vulkan. Best on modern GPUs.",
        engineArgs = listOf(
            "--rendering-method", "mobile", "--rendering-driver", "vulkan",
            "--use_immersive",
        ),
    ),
    GLES(
        label = "GLES",
        summary = "Force the OpenGL ES 3 compatibility renderer for older or unstable drivers.",
        engineArgs = listOf(
            "--rendering-method", "gl_compatibility", "--rendering-driver", "opengl3",
            "--use_immersive",
        ),
    );

    companion object {
        fun fromName(name: String?): Backend = entries.firstOrNull { it.name == name } ?: AUTO
    }
}
