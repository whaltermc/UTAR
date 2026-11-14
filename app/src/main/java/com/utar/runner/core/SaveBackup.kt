package com.utar.runner.core

import android.content.Context
import android.net.Uri
import com.utar.runner.runtime.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backs up and restores the game's user:// data — the app's filesDir, where Godot maps user://
 * and where the game keeps its saves and settings.
 *
 * The archive only contains game-owned data. The staged pack (`game/`), the runner's logs
 * (`logs/`, `session.log*`, `game.pid`) and the regenerable shader cache
 * (`.godot/shader_cache`) are excluded: the pack is huge and re-copyable via the install
 * flow, and the shader cache rebuilds itself on the next launch. Both directions run on
 * Dispatchers.IO and report progress through [onStage].
 */
class SaveBackup(private val context: Context) {

    suspend fun backup(target: Uri, onStage: (stage: String, progress: Float) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val root = context.filesDir
            val files = collectFiles(root)
            val total = files.sumOf { it.length() }
            SessionLog.i("Save backup: ${files.size} file(s), $total bytes")

            var copied = 0L
            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: throw IllegalStateException("Couldn't open the backup destination.")
            ZipOutputStream(output.buffered()).use { zip ->
                for (file in files) {
                    zip.putNextEntry(ZipEntry(relativePath(root, file)).apply {
                        time = file.lastModified()
                    })
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            zip.write(buffer, 0, read)
                            copied += read
                            if (total > 0L) {
                                val fraction = (copied.toFloat() / total).coerceIn(0f, 1f)
                                onStage("Backing up saves", 0.02f + 0.95f * fraction)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            SessionLog.i("Save backup complete: $copied bytes written")
            copied
        }

    /**
     * Replaces the current save data with the archive's contents. The archive is fully scanned
     * and every entry path validated *before* anything on disk is touched, so a bad or hostile
     * file fails without deleting the existing saves.
     */
    suspend fun restore(source: Uri, onStage: (stage: String, progress: Float) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val root = context.filesDir

            val names = ArrayList<String>()
            ZipInputStream(openSource(source).buffered()).use { zip ->
                var first = true
                while (true) {
                    val entry = zip.nextEntry ?: break
                    first = false
                    if (!entry.isDirectory && !excluded(entry.name)) {
                        val target = safePath(root, entry.name)
                            ?: throw IllegalStateException("Backup contains an unsafe path: ${entry.name}")
                        names.add(relativePath(root, target))
                    }
                }
                if (first) throw IllegalStateException("That file is not a save backup.")
            }
            if (names.isEmpty()) throw IllegalStateException("That backup contains no save files.")
            SessionLog.i("Save restore: ${names.size} file(s)")

            clearRestorable(root)

            var restored = 0
            ZipInputStream(openSource(source).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && !excluded(entry.name)) {
                        val target = safePath(root, entry.name) ?: return@use
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output -> zip.copyTo(output, BUFFER) }
                        restored++
                        val fraction = (restored.toFloat() / names.size).coerceIn(0f, 1f)
                        onStage("Restoring saves", 0.02f + 0.95f * fraction)
                    }
                }
            }
            SessionLog.i("Save restore complete: $restored file(s)")
            restored
        }

    private fun openSource(source: Uri) =
        context.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("Couldn't read the backup file.")

    /** Only game-owned data travels in the archive; the same scope is cleared on restore. */
    private fun excluded(relative: String): Boolean = when {
        relative == "game" || relative.startsWith("game/") -> true
        relative == "logs" || relative.startsWith("logs/") -> true
        relative == "session.log" || relative == "session.log.1" || relative == "game.pid" -> true
        relative == ".godot/shader_cache" || relative.startsWith(".godot/shader_cache/") -> true
        else -> false
    }

    private fun collectFiles(root: File): List<File> {
        val files = mutableListOf<File>()
        fun walk(dir: File, prefix: String) {
            dir.listFiles()?.forEach { child ->
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (excluded(relative)) return@forEach
                if (child.isDirectory) walk(child, relative) else files += child
            }
        }
        walk(root, "")
        return files
    }

    /** Deletes everything in the restorable scope so the archive fully replaces it. */
    private fun clearRestorable(root: File) {
        root.listFiles()?.forEach { child ->
            if (!excluded(child.name)) child.deleteRecursively()
        }
    }

    /** Turns an archive entry name into a file under [root], rejecting traversal outside it. */
    private fun safePath(root: File, name: String): File? {
        val parts = name.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return null
        val target = File(root, parts.joinToString("/"))
        val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        val targetPath = runCatching { target.canonicalPath }.getOrDefault(target.absolutePath)
        return if (targetPath.startsWith(rootPath + File.separator)) target else null
    }

    private fun relativePath(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath + File.separator)

    private companion object {
        const val BUFFER = 1 shl 16
    }
}
