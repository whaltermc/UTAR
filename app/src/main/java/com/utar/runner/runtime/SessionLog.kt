package com.utar.runner.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling session log kept in private storage. It records what the runner did (install,
 * patch decisions, engine command line, failures) so "Export latest log" always has
 * something useful even when the game never reaches its own logging.
 */
object SessionLog {

    private const val TAG = "UTAR"
    private const val MAX_BYTES = 512L * 1024
    private const val FILE_NAME = "session.log"

    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        val directory = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(directory, FILE_NAME)
        i("Session started on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
            "(Android ${android.os.Build.VERSION.RELEASE}, SDK ${android.os.Build.VERSION.SDK_INT}, " +
            "${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI"})")
    }

    fun i(message: String) = write("I", message)

    fun w(message: String) = write("W", message)

    fun e(message: String, error: Throwable? = null) =
        write("E", message + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))

    fun file(): File? = logFile

    fun contents(): String = logFile?.takeIf { it.isFile }?.readText().orEmpty()

    private fun write(level: String, message: String) {
        when (level) {
            "E" -> Log.e(TAG, message)
            "W" -> Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }
        val line = "${timestamp.format(Date())} $level $message"
        synchronized(this) {
            val target = logFile ?: return
            runCatching {
                if (target.length() > MAX_BYTES) target.writeText("")
                target.appendText(line + "\n")
            }
        }
    }
}
