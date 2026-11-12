package com.utar.runner.runtime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling session log kept in private storage. It records what the runner did (install,
 * patch decisions, engine command line, failures) so "Export latest log" always has
 * something useful even when the game never reaches its own logging.
 *
 * The launcher and the game (`:godot`) run in separate processes and both write here, and the
 * game logs from the UI thread *and* Godot's render thread, so every write is serialised and
 * tagged with the process it came from. Uncaught exceptions are logged too, which means a crash
 * in either process now leaves a stack trace in the exported log.
 */
object SessionLog {

    private const val TAG = "UTAR"
    private const val MAX_BYTES = 512L * 1024
    private const val FILE_NAME = "session.log"
    private const val OLD_FILE_NAME = "session.log.1"
    private const val PID_FILE = "game.pid"

    private val lock = Any()

    // SimpleDateFormat is not thread-safe; it is only ever touched while holding [lock].
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logFile: File? = null
    @Volatile private var processTag = "?"
    @Volatile private var startedAt = SystemClock.elapsedRealtime()

    fun init(context: Context) {
        val directory = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(directory, FILE_NAME)
        processTag = readProcessTag(context)
        startedAt = SystemClock.elapsedRealtime()
        installCrashHandler()
        i(
            "Session started on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "(Android ${android.os.Build.VERSION.RELEASE}, SDK ${android.os.Build.VERSION.SDK_INT}, " +
                "${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI"})",
        )
    }

    fun i(message: String) = write("I", message)

    fun w(message: String) = write("W", message)

    fun e(message: String, error: Throwable? = null) =
        write("E", message + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))

    /** Logs [label] with the milliseconds since this process started, for launch profiling. */
    fun mark(label: String) = write("I", "[+${SystemClock.elapsedRealtime() - startedAt}ms] $label")

    fun file(): File? = logFile

    /** Stores the game process id so the launcher can read its logcat after the process exits. */
    fun recordProcessId() {
        val directory = logFile?.parentFile ?: return
        runCatching { File(directory, PID_FILE).writeText(android.os.Process.myPid().toString()) }
    }

    fun recordedProcessId(): Int {
        val directory = logFile?.parentFile ?: return 0
        return runCatching { File(directory, PID_FILE).readText().trim().toInt() }.getOrDefault(0)
    }

    fun contents(): String {
        val current = logFile ?: return ""
        return synchronized(lock) {
            val old = File(current.parentFile, OLD_FILE_NAME)
            val oldText = old.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
            val currentText = current.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
            oldText + currentText
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write("E", "FATAL on thread '${thread.name}': ${Log.getStackTraceString(error)}") }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun readProcessTag(context: Context): String = runCatching {
        val name = File("/proc/self/cmdline").readText().trim('\u0000', ' ')
        if (name.contains(':')) name.substringAfterLast(':') else "main"
    }.getOrDefault(context.applicationInfo.packageName ?: "main")

    private fun write(level: String, message: String) {
        when (level) {
            "E" -> Log.e(TAG, message)
            "W" -> Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }
        synchronized(lock) {
            val target = logFile ?: return
            runCatching {
                if (target.length() > MAX_BYTES) {
                    val old = File(target.parentFile, OLD_FILE_NAME)
                    old.delete()
                    target.renameTo(old)
                }
                target.appendText("${timestamp.format(Date())} $level [$processTag] $message\n")
            }
        }
    }
}
