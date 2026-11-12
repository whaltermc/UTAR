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
import com.utar.runner.core.ProjectSettingsOverride
import com.utar.runner.runtime.Backend
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
        val busy: Boolean = false,
        val progress: Float = 0f,
        val stage: String = "",
        val notice: String? = null,
        val error: String? = null,
        val launching: Boolean = false,
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

    /**
     * Starts the engine. Everything slow happens off the main thread (the previous version opened
     * and indexed the whole pack on the UI thread here, which is the launch freeze), and the
     * launcher keeps animating its own "starting" screen until the game process reports that its
     * window has painted, or dies.
     */
    fun launchGame(pack: File) {
        val current = _state.value
        if (current.busy || current.launching) return
        val backend = current.backend
        _state.update { it.copy(launching = true, stage = "Launching", notice = null, error = null) }

        viewModelScope.launch {
            val context: Context = getApplication()
            SessionLog.mark("Launch requested (${backend.label})")

            val aligned = withContext(Dispatchers.IO) {
                runCatching { ProjectSettingsOverride.prepare(context, pack, backend) }
                    .onFailure { SessionLog.e("Renderer override failed", it) }
                    .getOrDefault(false)
            }
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

            val ready = CompletableDeferred<Unit>()
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    ready.complete(Unit)
                }
            }
            try {
                context.startActivity(GameActivity.createIntent(context, pack, backend, receiver))
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
        var seenAlive = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (withTimeoutOrNull(POLL_MS) { ready.await() } != null) return LaunchOutcome.READY
            val alive = withContext(Dispatchers.IO) { isProcessAlive(context, processName) }
            if (alive) {
                seenAlive = true
            } else if (seenAlive) {
                return LaunchOutcome.CRASHED
            }
        }
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
