package com.utar.runner.runtime

import android.content.Context

enum class ThemeMode {
    System, Light, Dark
}

data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val useDynamicColor: Boolean = true,
    val accentColorArgb: Int = 0xFFE9C7A3.toInt(),
)

class RunnerPrefs(context: Context) {

    data class InstallRecord(
        val fileName: String,
        val sizeBytes: Long,
        val installedAt: Long,
        val patchedExtensions: Int,
    )

    private val prefs = context.applicationContext.getSharedPreferences("utar_runner", Context.MODE_PRIVATE)

    var backend: Backend
        get() = Backend.fromName(prefs.getString(KEY_BACKEND, null))
        set(value) {
            prefs.edit().putString(KEY_BACKEND, value.name).apply()
        }

    var themeMode: ThemeMode
        get() {
            val name = prefs.getString(KEY_THEME_MODE, null)
            return if (name != null) ThemeMode.valueOf(name) else ThemeMode.System
        }
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    var useDynamicColor: Boolean
        get() = prefs.getBoolean(KEY_USE_DYNAMIC_COLOR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_DYNAMIC_COLOR, value).apply()
        }

    var accentColorArgb: Int
        get() = prefs.getInt(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR)
        set(value) {
            prefs.edit().putInt(KEY_ACCENT_COLOR, value).apply()
        }

    fun recordInstall(record: InstallRecord) {
        prefs.edit()
            .putString(KEY_INSTALL_NAME, record.fileName)
            .putLong(KEY_INSTALL_SIZE, record.sizeBytes)
            .putLong(KEY_INSTALL_TIME, record.installedAt)
            .putInt(KEY_INSTALL_PATCHED, record.patchedExtensions)
            .apply()
    }

    fun installRecord(): InstallRecord? {
        val name = prefs.getString(KEY_INSTALL_NAME, null) ?: return null
        return InstallRecord(
            fileName = name,
            sizeBytes = prefs.getLong(KEY_INSTALL_SIZE, 0L),
            installedAt = prefs.getLong(KEY_INSTALL_TIME, 0L),
            patchedExtensions = prefs.getInt(KEY_INSTALL_PATCHED, 0),
        )
    }

    fun clearInstall() {
        prefs.edit()
            .remove(KEY_INSTALL_NAME)
            .remove(KEY_INSTALL_SIZE)
            .remove(KEY_INSTALL_TIME)
            .remove(KEY_INSTALL_PATCHED)
            .remove(KEY_STAGED_SIZE)
            .remove(KEY_STAGED_MTIME)
            .remove(KEY_SOURCE_URI)
            .apply()
    }

    /** Cheap identity of the staged pack: size + lastModified, no full hash. */
    data class StagedPackInfo(val sizeBytes: Long, val lastModifiedMs: Long)

    fun stagedPackInfo(): StagedPackInfo? {
        val size = prefs.getLong(KEY_STAGED_SIZE, -1L)
        val lastModified = prefs.getLong(KEY_STAGED_MTIME, -1L)
        if (size < 0L || lastModified < 0L) return null
        return StagedPackInfo(size, lastModified)
    }

    fun recordStagedPack(info: StagedPackInfo) {
        prefs.edit()
            .putLong(KEY_STAGED_SIZE, info.sizeBytes)
            .putLong(KEY_STAGED_MTIME, info.lastModifiedMs)
            .apply()
    }

    /** Persistable source URI, so a changed/missing staged pack can be re-copied later. */
    fun sourceUri(): String? = prefs.getString(KEY_SOURCE_URI, null)

    fun storeSourceUri(value: String) {
        prefs.edit().putString(KEY_SOURCE_URI, value).apply()
    }

    fun renderingMethodOriginal(): String? = prefs.getString(KEY_RENDERING_METHOD_ORIGINAL, null)

    fun storeRenderingMethodOriginal(value: String) {
        prefs.edit().putString(KEY_RENDERING_METHOD_ORIGINAL, value).apply()
    }

    /** Remembers which (backend, pack size, pack mtime) the pack's renderer setting was last aligned for. */
    var rendererOverrideStamp: String?
        get() = prefs.getString(KEY_OVERRIDE_STAMP, null)
        set(value) {
            prefs.edit().putString(KEY_OVERRIDE_STAMP, value).apply()
        }

    /** Cached location of the rendering_method string inside the installed pack. */
    data class RendererSlot(
        val path: String,
        val valueOffset: Long,
        val valueLength: Int,
    )

    fun rendererSlot(): RendererSlot? {
        val path = prefs.getString(KEY_SLOT_PATH, null) ?: return null
        if (!prefs.contains(KEY_SLOT_OFFSET) || !prefs.contains(KEY_SLOT_LENGTH)) return null
        val length = prefs.getInt(KEY_SLOT_LENGTH, -1)
        if (length <= 0) return null
        return RendererSlot(path, prefs.getLong(KEY_SLOT_OFFSET, -1L), length)
    }

    fun storeRendererSlot(slot: RendererSlot) {
        prefs.edit()
            .putString(KEY_SLOT_PATH, slot.path)
            .putLong(KEY_SLOT_OFFSET, slot.valueOffset)
            .putInt(KEY_SLOT_LENGTH, slot.valueLength)
            .apply()
    }

    /** Forget the renderer bookkeeping; call whenever the installed pack changes. */
    fun resetRendererOverride() {
        prefs.edit()
            .remove(KEY_RENDERING_METHOD_ORIGINAL)
            .remove(KEY_OVERRIDE_STAMP)
            .remove(KEY_SLOT_PATH)
            .remove(KEY_SLOT_OFFSET)
            .remove(KEY_SLOT_LENGTH)
            .apply()
    }

    private companion object {
        const val KEY_OVERRIDE_STAMP = "renderer_override_stamp"
        const val KEY_BACKEND = "backend"
        const val KEY_INSTALL_NAME = "install_name"
        const val KEY_INSTALL_SIZE = "install_size"
        const val KEY_INSTALL_TIME = "install_time"
        const val KEY_INSTALL_PATCHED = "install_patched"
        const val KEY_STAGED_SIZE = "staged_size"
        const val KEY_STAGED_MTIME = "staged_mtime"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_RENDERING_METHOD_ORIGINAL = "rendering_method_original"
        const val KEY_SLOT_PATH = "renderer_slot_path"
        const val KEY_SLOT_OFFSET = "renderer_slot_offset"
        const val KEY_SLOT_LENGTH = "renderer_slot_length"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_USE_DYNAMIC_COLOR = "use_dynamic_color"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val DEFAULT_ACCENT_COLOR = 0xFFE9C7A3.toInt()
    }
}
