package com.utar.runner.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.utar.runner.core.GameInstaller
import com.utar.runner.runtime.Backend
import com.utar.runner.runtime.LogExport
import com.utar.runner.runtime.RunnerPrefs
import com.utar.runner.runtime.SessionLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val backend: Backend = Backend.AUTO,
        val packFile: File? = null,
        val packName: String = "",
        val packBytes: Long = 0L,
        val patchedExtensions: Int = 0,
        val busy: Boolean = false,
        val progress: Float = 0f,
        val stage: String = "",
        val notice: String? = null,
        val error: String? = null,
    )

    private val prefs = RunnerPrefs(application)
    private val installer = GameInstaller(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val record = prefs.installRecord()
        val file = installer.installedPack()
        _state.value = UiState(
            backend = prefs.backend,
            packFile = file,
            packName = record?.fileName ?: file?.name.orEmpty(),
            packBytes = file?.length() ?: 0L,
            patchedExtensions = record?.patchedExtensions ?: 0,
        )
    }

    fun selectBackend(backend: Backend) {
        prefs.backend = backend
        _state.update { it.copy(backend = backend) }
    }

    fun dismissMessages() {
        _state.update { it.copy(notice = null, error = null) }
    }

    fun install(uri: Uri, displayName: String?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, progress = 0f, stage = "Starting", notice = null, error = null) }
        viewModelScope.launch {
            try {
                val outcome = installer.install(uri, displayName) { stage, progress ->
                    _state.update { current -> current.copy(stage = stage, progress = progress) }
                }
                prefs.recordInstall(
                    RunnerPrefs.InstallRecord(
                        fileName = outcome.sourceName,
                        sizeBytes = outcome.sizeBytes,
                        installedAt = System.currentTimeMillis(),
                        patchedExtensions = outcome.patchedCount,
                    ),
                )
                _state.update { current ->
                    current.copy(
                        busy = false,
                        progress = 1f,
                        stage = "",
                        packFile = outcome.packFile,
                        packName = outcome.sourceName,
                        packBytes = outcome.sizeBytes,
                        patchedExtensions = outcome.patchedCount,
                        notice = buildString {
                            append("Installed ${outcome.entryCount} files into private storage.")
                            if (outcome.patchedCount > 0) {
                                append(" Rebuilt ${outcome.patchedCount} extension config(s) for Android.")
                            }
                        },
                    )
                }
            } catch (error: Throwable) {
                SessionLog.e("Install failed", error)
                _state.update {
                    it.copy(busy = false, stage = "", error = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun exportLog(target: Uri) {
        val snapshot = _state.value
        viewModelScope.launch {
            try {
                val bytes = LogExport.export(getApplication(), target, snapshot.packFile, snapshot.backend)
                _state.update { it.copy(notice = "Log exported ($bytes bytes).", error = null) }
            } catch (error: Throwable) {
                SessionLog.e("Log export failed", error)
                _state.update { it.copy(error = error.message ?: "Log export failed.") }
            }
        }
    }
}
