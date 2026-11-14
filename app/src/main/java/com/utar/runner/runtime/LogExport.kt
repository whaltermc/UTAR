package com.utar.runner.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Assembles the runner log, engine logcat output and the game's own log file into one report. */
object LogExport {

    private const val MAX_SECTION_CHARS = 200_000

    suspend fun export(
        context: Context,
        target: Uri,
        packFile: File?,
        backend: Backend,
    ): Long = withContext(Dispatchers.IO) {
        val report = buildReport(context, packFile, backend)
        val bytes = report.toByteArray(Charsets.UTF_8)
        context.contentResolver.openOutputStream(target, "wt")?.use { output ->
            output.write(bytes)
        } ?: throw IllegalStateException("Couldn't open the selected destination.")
        SessionLog.i("Log exported to $target (${bytes.size} bytes)")
        bytes.size.toLong()
    }

    fun buildReport(context: Context, packFile: File?, backend: Backend): String = buildString {
        appendLine("UTAR · Until Then Android Runner")
        appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Graphics backend: ${backend.label} ${backend.engineArgs.joinToString(" ")}".trim())
        appendLine(
            "Pack: " + when {
                packFile != null && packFile.isFile -> "${packFile.absolutePath} (${packFile.length()} bytes)"
                else -> "not installed"
            },
        )
        appendLine()
        appendLine("=== Runner session log ===")
        appendLine(SessionLog.contents().ifBlank { "(empty)" })
        appendLine()
        appendLine("=== Engine output (captured during the session) ===")
        appendLine(capturedLogcat(context) ?: "(no captured engine output)")
        appendLine()
        appendLine("=== Engine output (logcat ring buffer, launcher + game process) ===")
        appendLine(logcat().ifBlank { "(no engine output captured)" })
        appendLine()
        appendLine("=== Android crash log (logcat -b crash) ===")
        appendLine(crashLogcat().ifBlank { "(no crash logcat captured)" })
        appendLine()
        appendLine("=== Game log (user://logs/godot.log) ===")
        appendLine(godotLog(context) ?: "(no Godot log file found)")
        appendLine()
        appendLine("=== Game scripts (from the staged pack, captured at install) ===")
        appendLine(scriptDump(context) ?: "(no script dump found)")
    }

    /** Scripts the installer extracted from the pack, for diagnosing in-game behaviour. */
    private fun scriptDump(context: Context): String? =
        File(context.filesDir, "logs/scripts-dump.txt")
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeLast(MAX_SECTION_CHARS)

    private fun logcat(): String {
        val pids = LinkedHashSet<Int>()
        pids += android.os.Process.myPid()
        SessionLog.recordedProcessId().takeIf { it > 0 }?.let { pids += it }

        val sections = pids.mapNotNull { pid ->
            runCatching {
                val process = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=$pid")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                output.takeIf { it.isNotBlank() }?.let { "--- pid $pid ---\n$it" }
            }.getOrNull()
        }
        return sections.joinToString("\n\n").takeLast(MAX_SECTION_CHARS)
    }

    private fun capturedLogcat(context: Context): String? =
        File(context.filesDir, "logs/godot-logcat.txt")
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeLast(MAX_SECTION_CHARS)

    private fun crashLogcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-b", "crash")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.takeIf { it.isNotBlank() }
    }.getOrNull() ?: ""

    private fun godotLog(context: Context): String? {
        val candidates = mutableListOf<File>()
        fun walk(directory: File, depth: Int) {
            if (depth > 4) return
            val children = directory.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) walk(child, depth + 1) else if (child.name == "godot.log") candidates += child
            }
        }
        walk(context.filesDir, 0)
        val newest = candidates.maxByOrNull { it.lastModified() } ?: return null
        return newest.readText().takeLast(MAX_SECTION_CHARS)
    }
}
