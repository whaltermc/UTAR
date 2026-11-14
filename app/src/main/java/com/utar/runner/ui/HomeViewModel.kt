package com.utar.runner.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.utar.runner.GameActivity
import com.utar.runner.core.GameInstaller
import com.utar.runner.core.PackWarmup
import com.utar.runner.core.ProjectSettingsOverride
import com.utar.runner.core.SaveBackup
import com.utar.runner.runtime.Backend
import com.utar.runner.runtime.DebugLog
import com.utar.runner.runtime.LogExport
import com.utar.runner.runtime.RunnerPrefs
import com.utar.runner.runtime.SessionLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val backend: Backend = Backend.AUTO,
        val packFile: File? = null,
        val packName: String = "",
        val packBytes: Long = 0L,
        val patchedExtensions: Int = 0,
        /** False until the cheap installed-pack scan finishes; the UI renders immediately. */
        val ready: Boolean = false,
        val busy: Boolean = false,
        val progress: Float = 0f,
        val stage: String = "",
        val notice: String? = null,
        val error: String? = null,
        val launching: Boolean = false,
    )

    private val prefs = RunnerPrefs(application)
    private val installer = GameInstaller(application)
    private val saveBackup = SaveBackup(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // The Compose screen composes straight away; only this tiny scan waits. No pack
        // indexing or extension checks happen here — those live in the install/launch flows.
        viewModelScope.launch {
            val (record, file) = withContext(Dispatchers.IO) {
                prefs.installRecord() to installer.installedPack()
            }
            _state.value = UiState(
                backend = prefs.backend,
                packFile = file,
                packName = record?.fileName ?: file?.name.orEmpty(),
                packBytes = file?.length() ?: 0L,
                patchedExtensions = record?.patchedExtensions ?: 0,
                ready = true,
            )
        }
    }

    fun selectBackend(backend: Backend) {
        prefs.backend = backend
        _state.update { it.copy(backend = backend) }
    }

    fun dismissMessages() {
        _state.update { it.copy(notice = null, error = null) }
    }

    /** Copies the game's user:// save data (minus pack/logs/shader cache) into a zip document. */
    fun backupSaves(uri: Uri) {
        val current = _state.value
        if (current.busy || current.launching) return
        _state.update { it.copy(busy = true, progress = 0f, stage = "Backing up saves", notice = null, error = null) }
        viewModelScope.launch {
            try {
                val bytes = saveBackup.backup(uri) { stage, progress ->
                    _state.update { it.copy(stage = stage, progress = progress) }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        progress = 1f,
                        stage = "",
                        notice = "Saves backed up (" + String.format(
                            java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0,
                        ) + ").",
                    )
                }
            } catch (error: Throwable) {
                SessionLog.e("Save backup failed", error)
                _state.update {
                    it.copy(busy = false, stage = "", error = error.message ?: "Save backup failed.")
                }
            }
        }
    }

    /** Replaces the game's user:// save data with the contents of a backup zip. */
    fun restoreSaves(uri: Uri) {
        val current = _state.value
        if (current.busy || current.launching) return
        _state.update { it.copy(busy = true, progress = 0f, stage = "Restoring saves", notice = null, error = null) }
        viewModelScope.launch {
            try {
                val count = saveBackup.restore(uri) { stage, progress ->
                    _state.update { it.copy(stage = stage, progress = progress) }
                }
                _state.update {
                    it.copy(busy = false, progress = 1f, stage = "", notice = "Restored $count save file(s).")
                }
            } catch (error: Throwable) {
                SessionLog.e("Save restore failed", error)
                _state.update {
                    it.copy(busy = false, stage = "", error = error.message ?: "Save restore failed.")
                }
            }
        }
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

    /**
     * Starts the engine. The launch pipeline runs entirely off the main thread — staging
     * validation/copy, renderer alignment, page-cache warmup — and only then the Godot
     * activity is started. The launcher keeps animating its own "starting" screen until the
     * game process reports that its window has painted, or dies.
     */
    fun launchGame(pack: File) {
        val current = _state.value
        if (current.busy || current.launching) return
        val backend = current.backend
        _state.update { it.copy(launching = true, stage = "Launching", notice = null, error = null) }

        viewModelScope.launch {
            val context: Context = getApplication()
            DebugLog.timing("Launch pipeline: start (${backend.label})")
            SessionLog.mark("Launch requested (${backend.label})")

            val staged = try {
                DebugLog.timing("Stage copy: begins")
                val stageStarted = SystemClock.elapsedRealtime()
                val file = installer.ensureStaged { stage, progress ->
                    _state.update { it.copy(stage = stage, progress = progress) }
                }
                DebugLog.timing("Stage copy: finished in ${SystemClock.elapsedRealtime() - stageStarted}ms")
                file
            } catch (error: Throwable) {
                SessionLog.e("Staging failed", error)
                _state.update {
                    it.copy(launching = false, stage = "", error = error.message ?: "Staging failed.")
                }
                return@launch
            }

            DebugLog.timing("Renderer alignment: begins")
            val aligned = withContext(Dispatchers.IO) {
                runCatching { ProjectSettingsOverride.prepare(context, staged, backend) }
                    .onFailure { SessionLog.e("Renderer override failed", it) }
                    .getOrDefault(false)
            }
            DebugLog.timing("Renderer alignment: finished (aligned=$aligned)")
            if (!aligned) {
                _state.update {
                    it.copy(
                        launching = false,
                        stage = "",
                        error = "The ${backend.label} backend isn't available for this package.",
                    )
                }
                return@launch
            }

            DebugLog.timing("Page-cache warmup: begins")
            val warmStarted = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) { PackWarmup.warm(staged) }
            DebugLog.timing("Page-cache warmup: finished in ${SystemClock.elapsedRealtime() - warmStarted}ms")

            val ready = CompletableDeferred<Unit>()
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    ready.complete(Unit)
                }
            }
            try {
                DebugLog.timing("Godot activity start: startActivity")
                context.startActivity(GameActivity.createIntent(context, staged, backend, receiver))
                DebugLog.timing("Godot activity start: startActivity returned")
            } catch (error: Throwable) {
                SessionLog.e("Couldn't start the game activity", error)
                _state.update { it.copy(launching = false, stage = "", error = error.message ?: "Couldn't start the game.") }
                return@launch
            }

            val outcome = awaitGameWindow(context, ready)
            SessionLog.mark("Launch finished: $outcome")
            _state.update {
                it.copy(
                    launching = false,
                    stage = "",
                    error = if (outcome == LaunchOutcome.CRASHED) {
                        "The game process stopped before it started. Export the log to see why."
                    } else {
                        null
                    },
                )
            }
        }
    }

    private enum class LaunchOutcome { READY, CRASHED, TIMED_OUT }

    private suspend fun awaitGameWindow(context: Context, ready: CompletableDeferred<Unit>): LaunchOutcome {
        val processName = "${context.packageName}:godot"
        val deadline = SystemClock.elapsedRealtime() + LAUNCH_TIMEOUT_MS
        val waitStarted = SystemClock.elapsedRealtime()
        var seenAlive = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (withTimeoutOrNull(POLL_MS) { ready.await() } != null) {
                SessionLog.mark("Launcher: game window ready (waited ${SystemClock.elapsedRealtime() - waitStarted}ms)")
                return LaunchOutcome.READY
            }
            val alive = withContext(Dispatchers.IO) { isProcessAlive(context, processName) }
            if (alive) {
                seenAlive = true
            } else if (seenAlive) {
                SessionLog.mark("Launcher: game process crashed after ${SystemClock.elapsedRealtime() - waitStarted}ms")
                return LaunchOutcome.CRASHED
            }
        }
        SessionLog.mark("Launcher: game window timeout after ${SystemClock.elapsedRealtime() - waitStarted}ms")
        return LaunchOutcome.TIMED_OUT
    }

    private fun isProcessAlive(context: Context, processName: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses?.any { it.processName == processName } == true
    }.getOrDefault(true)

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

    private companion object {
        const val POLL_MS = 1_000L
        const val LAUNCH_TIMEOUT_MS = 5L * 60L * 1_000L
    }
}
