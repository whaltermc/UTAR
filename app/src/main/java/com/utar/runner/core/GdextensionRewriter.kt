package com.utar.runner.core

/**
 * Rewrites a `.gdextension` config so the engine picks the Android build of the extension
 * instead of the desktop binary the game was exported with.
 *
 * Desktop exports only declare libraries such as `windows.x86_64 = "res://bin/libfoo.windows.x86_64.dll"`.
 * On Android no line matches, so the extension silently fails to load. We add `android.<arch>`
 * entries that point at a path inside the pack that deliberately does not exist: Godot 4.1's
 * `OS_Android::open_dynamic_library` then falls back to the bare library name, which the linker
 * resolves from the APK's native library directory populated from `natives/android/<abi>/`.
 *
 * Which native library satisfies a config is decided by its `entry_symbol` when the built
 * libraries expose it (the reliable signal), falling back to matching library names.
 */
object GdextensionRewriter {

    data class Plan(
        val configPath: String,
        val text: String,
        /** ABI directory (e.g. `arm64-v8a`) to the native library that will satisfy this config. */
        val libraries: Map<String, String>,
    ) {
        val patched: Boolean get() = libraries.isNotEmpty()
    }

    private const val LIBRARIES_SECTION = "[libraries]"
    private const val CONFIGURATION_SECTION = "[configuration]"
    private const val ANDROID_PATH_ROOT = "res://utar_natives"

    fun plan(configPath: String, original: String, natives: NativeLibrarySet): Plan {
        val entrySymbol = configurationValue(original, "entry_symbol")
        val matches = matchByEntrySymbol(natives, entrySymbol)
            .ifEmpty { matchByLibraryName(original, natives) }
        if (matches.isEmpty()) return Plan(configPath, original, emptyMap())

        val lines = matches.map { (abiDirectory, library) ->
            "android.${NativeLibrarySet.featureTag(abiDirectory)} = \"$ANDROID_PATH_ROOT/$abiDirectory/$library\""
        }
        val text = appendToLibraries(stripAndroidEntries(original), lines)
        return Plan(configPath, text, matches.toMap())
    }

    private fun matchByEntrySymbol(
        natives: NativeLibrarySet,
        entrySymbol: String?,
    ): List<Pair<String, String>> {
        if (entrySymbol.isNullOrBlank()) return emptyList()
        return natives.libraries.mapNotNull { (abiDirectory, available) ->
            val library = available.firstOrNull { entrySymbol in it.entrySymbols } ?: return@mapNotNull null
            abiDirectory to library.fileName
        }
    }

    private fun matchByLibraryName(
        original: String,
        natives: NativeLibrarySet,
    ): List<Pair<String, String>> {
        val roots = declaredRoots(original)
        val all = natives.libraries.values.flatten()
        if (all.isEmpty()) return emptyList()

        val matching = all.filter { library ->
            val candidate = normalize(rootOf(library.fileName))
            roots.any { root -> candidate.isNotEmpty() && (candidate.startsWith(root) || root.startsWith(candidate)) }
        }
        val chosen = when {
            matching.isNotEmpty() -> matching
            all.size == 1 -> all // A single Android build can only belong to this one config.
            else -> emptyList()
        }
        if (chosen.isEmpty()) return emptyList()

        return natives.libraries.mapNotNull { (abiDirectory, available) ->
            val library = chosen.firstOrNull { it in available } ?: return@mapNotNull null
            abiDirectory to library.fileName
        }
    }

    /** First path segment of every declared library, e.g. `libfoo.windows.x86_64.dll` -> `libfoo`. */
    private fun declaredRoots(config: String): List<String> {
        val libraries = sectionLines(config, LIBRARIES_SECTION)
        return libraries.mapNotNull { line ->
            val path = line.substringAfter('=', "").trim().trim('"')
            if (path.isEmpty()) null else normalize(rootOf(path))
        }.filter { it.isNotEmpty() }
    }

    private fun configurationValue(config: String, key: String): String? =
        sectionLines(config, CONFIGURATION_SECTION)
            .firstOrNull { it.substringBefore('=').trim().equals(key, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }

    private fun rootOf(path: String): String {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val withoutExtension = fileName
            .removeSuffix(".so")
            .removeSuffix(".dll")
            .removeSuffix(".dylib")
            .removeSuffix(".lib")
            .removeSuffix(".wasm")
        return withoutExtension.substringBefore('.')
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun stripAndroidEntries(config: String): String =
        config.lines()
            .filterNot { it.trimStart().startsWith("android.", ignoreCase = true) }
            .joinToString("\n")

    private fun appendToLibraries(config: String, lines: List<String>): String {
        val all = config.lines().toMutableList()
        val sectionIndex = all.indexOfFirst { it.trim().equals(LIBRARIES_SECTION, ignoreCase = true) }

        if (sectionIndex < 0) {
            val builder = StringBuilder(config.trimEnd('\n'))
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(LIBRARIES_SECTION).append('\n')
            lines.forEach { builder.append(it).append('\n') }
            return builder.toString()
        }

        var insertAt = sectionIndex + 1
        while (insertAt < all.size && !isSectionHeader(all[insertAt])) insertAt++
        // Keep the blank separator that usually precedes the next section.
        while (insertAt > sectionIndex + 1 && all[insertAt - 1].isBlank()) insertAt--

        all.addAll(insertAt, lines)
        return all.joinToString("\n")
    }

    private fun isSectionHeader(line: String): Boolean =
        line.trim().startsWith("[") && line.trim().endsWith("]")

    private fun sectionLines(config: String, section: String): List<String> {
        val result = mutableListOf<String>()
        var current = ""
        for (line in config.lines()) {
            val trimmed = line.trim()
            if (isSectionHeader(trimmed)) {
                current = trimmed
                continue
            }
            if (current.equals(section, ignoreCase = true) && trimmed.isNotEmpty() && '=' in trimmed) {
                result += trimmed
            }
        }
        return result
    }
}
