package com.utar.runner.runtime

import android.content.Context

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
            .apply()
    }

    private companion object {
        const val KEY_BACKEND = "backend"
        const val KEY_INSTALL_NAME = "install_name"
        const val KEY_INSTALL_SIZE = "install_size"
        const val KEY_INSTALL_TIME = "install_time"
        const val KEY_INSTALL_PATCHED = "install_patched"
    }
}
