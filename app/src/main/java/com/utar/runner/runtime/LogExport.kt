package com.utar.runner.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Assembles runner logs, engine output and pack patch logs into a zip archive. */
object LogExport {

    private const val MAX_SECTION_CHARS = 200_000

    suspend fun export(
        context: Context,
        target: Uri,
        packFile: File?,
        backend: Backend,
    ): Long = withContext(Dispatchers.IO) {
        var total = 0L
        context.contentResolver.openOutputStream(target, "wt")?.use { raw ->
            val zip = ZipOutputStream(raw)
            fun add(name: String, text: String) {
                if (text.isBlank()) return
                val entry = ZipEntry(name)
                zip.putNextEntry(entry)
                val bytes = text.toByteArray(Charsets.UTF_8)
                zip.write(bytes)
                zip.closeEntry()
                total += bytes.size
            }
            add("report.txt", buildReport(context, packFile, backend))
            add("session.log", SessionLog.contents())
            add("pck-patch.log", PckPatchLog.contents(context))
            add("godot-logcat.txt", capturedLogcat(context).orEmpty())
            add("logcat-ring.txt", logcat())
            add("crash-logcat.txt", crashLogcat())
            add("godot.log", godotLog(context).orEmpty())
            add("scripts-dump.txt", scriptDump(context).orEmpty())
            zip.close()
        } ?: throw IllegalStateException("Couldn't open the selected destination.")
        SessionLog.i("Log exported to $target ($total bytes)")
        total
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
    }

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
