package com.utar.runner.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import com.utar.runner.runtime.RunnerPrefs
import com.utar.runner.runtime.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs a game pack into the app's private storage and rebuilds it so the Android builds
 * of the game's GDExtensions are loaded instead of the desktop-only ones.
 *
 * The copy is what makes loading fast: the engine reads the pack from internal storage
 * instead of streaming it out of a content provider on every launch. Later launches never
 * copy again — the staged pack is validated by size + lastModified only, and the source
 * content URI is kept around so a missing/changed stage can be re-copied from background.
 */
class GameInstaller(private val context: Context) {

    private val prefs = RunnerPrefs(context)

    data class Outcome(
        val packFile: File,
        val sourceName: String,
        val sizeBytes: Long,
        val entryCount: Int,
        val plans: List<GdextensionRewriter.Plan>,
        val patchedCount: Int,
    )

    fun installedPack(): File? = packFile().takeIf { it.isFile }

    /**
     * Launch-time staging check. Returns the staged pack when its size + lastModified still
     * match what was recorded at install time — no copy, no hash, no pack indexing. Only when
     * the stage is missing or changed does it re-copy from the persisted source URI, on IO.
     */
    suspend fun ensureStaged(
        onStage: (stage: String, progress: Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = packFile()
        val record = prefs.stagedPackInfo()
        if (target.isFile && record != null &&
            target.length() == record.sizeBytes &&
            target.lastModified() == record.lastModifiedMs
        ) {
            SessionLog.mark("Staging: staged pack matches (size+mtime) — no copy needed")
            return@withContext target
        }

        SessionLog.mark("Staging: staged pack missing or changed — re-copying from source")
        val sourceUri = prefs.sourceUri()
            ?: throw IllegalStateException(
                "The installed game data is missing or changed. Choose your .pck again.",
            )
        install(Uri.parse(sourceUri), prefs.installRecord()?.fileName, onStage).packFile
    }

    fun clear() {
        workDirectory().deleteRecursively()
        RunnerPrefs(context).apply {
            clearInstall()
            resetRendererOverride()
        }
    }

    suspend fun install(
        uri: Uri,
        fallbackName: String?,
        onStage: (stage: String, progress: Float) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val directory = workDirectory().apply { mkdirs() }
        val incoming = File(directory, INCOMING_NAME)
        val target = File(directory, PACK_NAME)
        val patched = File(directory, "$PACK_NAME.tmp")
        listOf(incoming, patched).forEach { if (it.exists()) it.delete() }

        val sourceName = displayName(uri) ?: fallbackName?.takeIf { it.isNotBlank() } ?: "game.pck"
        SessionLog.i("Install started: $sourceName ($uri)")

        onStage("Copying into private storage", 0.02f)
        val copyStarted = SystemClock.elapsedRealtime()
        val expectedSize = contentSize(uri)
        context.contentResolver.openInputStream(uri)?.use { input ->
            incoming.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (expectedSize != null && expectedSize > 0L) {
                        val fraction = (copied.toFloat() / expectedSize).coerceIn(0f, 1f)
                        onStage("Copying into private storage", 0.02f + 0.60f * fraction)
                    }
                }
            }
        } ?: throw IllegalStateException("The selected file could not be read.")
        SessionLog.mark("Install: pack copied in ${SystemClock.elapsedRealtime() - copyStarted}ms")

        val copiedSize = incoming.length()
        if (copiedSize < 16L) {
            incoming.delete()
            throw IllegalStateException("That file is too small to be a Godot pack.")
        }

        val packOpenStarted = SystemClock.elapsedRealtime()
        val pack = try {
            PckPack.open(incoming)
        } catch (error: Throwable) {
            incoming.delete()
            throw error
        }
        SessionLog.mark("Install: pack opened/indexed in ${SystemClock.elapsedRealtime() - packOpenStarted}ms")
        SessionLog.i(
            "Pack: engine ${pack.engineVersion}, format v${pack.packVersion}, " +
                "${pack.entries.size} entries, $copiedSize bytes",
        )

        val nativesScanStarted = SystemClock.elapsedRealtime()
        val apks = buildList {
            context.applicationInfo.sourceDir?.let { add(File(it)) }
            context.applicationInfo.splitSourceDirs?.forEach { add(File(it)) }
        }
        val packaged = NativeLibrarySet.fromApksCached(apks, deviceAbiDirectory(), context.cacheDir)
        val natives = if (!packaged.isEmpty) {
            packaged
        } else {
            NativeLibrarySet.fromDirectory(
                File(context.applicationInfo.nativeLibraryDir.orEmpty()),
                deviceAbiDirectory(),
            )
        }
        SessionLog.mark("Install: native library discovery in ${SystemClock.elapsedRealtime() - nativesScanStarted}ms")
        SessionLog.i(
            "Android extension libraries: " +
                natives.allLibraries.joinToString().ifEmpty { "none (drop builds into natives/android/<abi>/)" },
        )

        val plans = mutableListOf<GdextensionRewriter.Plan>()
        val replacements = LinkedHashMap<String, ByteArray>()
        val gdextStart = SystemClock.elapsedRealtime()
        for (entry in pack.entries) {
            if (!entry.path.endsWith(".gdextension", ignoreCase = true)) continue
            if (entry.isEncrypted || entry.size > MAX_CONFIG_BYTES) {
                SessionLog.w("Skipping unreadable extension config ${entry.path}")
                continue
            }
            val original = String(pack.readBytes(entry), Charsets.UTF_8)
            val plan = GdextensionRewriter.plan(entry.path, original, natives)
            plans += plan
            if (plan.patched) {
                replacements[entry.path] = plan.text.toByteArray(Charsets.UTF_8)
                SessionLog.i(
                    "Extension ${entry.path} -> " +
                        plan.libraries.entries.joinToString { "${it.key}/${it.value}" },
                )
            } else {
                SessionLog.w("No Android build matched ${entry.path}; it will fail to load on device")
            }
        }
        SessionLog.mark("Install: GDExtension matching for ${plans.size} config(s) in ${SystemClock.elapsedRealtime() - gdextStart}ms")

        val additions = LinkedHashMap<String, ByteArray>()
        val storeEntry = pack.entries.firstOrNull { it.path == STORE_SCRIPT }
        if (storeEntry != null && !storeEntry.isEncrypted && storeEntry.size <= MAX_CONFIG_BYTES) {
            val original = String(pack.readBytes(storeEntry), Charsets.UTF_8)
            if (JSON_UTILS_DECLARATION in original) {
                val shim = readJsonShim()
                if (shim != null) {
                    replacements[STORE_SCRIPT] = original
                        .replace(JSON_UTILS_DECLARATION, JSON_SHIM_DECLARATION)
                        .toByteArray(Charsets.UTF_8)
                    additions[JSON_SHIM_PATH] = shim
                    SessionLog.i("Store.gd: replaced the jsonutils GDExtension with the GDScript shim")
                } else {
                    SessionLog.w("jsonutils shim missing from APK assets; Store.gd will fail to load")
                }
            }
        }

        // Fix known GDScript issues in every script the pack carries — parse errors on the
        // target engine (e.g. load_threaded_request called with a 5th argument) that keep a
        // script from compiling. Over-argued calls are trimmed to the documented maximum.
        //
        // Deliberately NOT applied to SceneView.gd: trimming its 5-arg call makes the script
        // compile for the first time on 4.1.4, activating a per-line scene-art loader that
        // feeds dialogue text into ResourceLoader.load ("No loader found" spam) and leaves
        // whole scenes black. UTAP ships that script unpatched (it simply fails to compile)
        // and the game renders fine through its normal path, so parity wins here.
        val scriptScanStarted = SystemClock.elapsedRealtime()
        var scriptFixCount = 0
        for (entry in pack.entries) {
            if (!entry.path.endsWith(".gd", ignoreCase = true)) continue
            if (entry.path == SCENE_VIEW_SCRIPT) continue
            if (entry.isEncrypted || entry.size <= 0L || entry.size > MAX_CONFIG_BYTES) continue
            val original = String(pack.readBytes(entry), Charsets.UTF_8)
            val (patched, fixes) = ScriptPatcher.fix(original)
            if (fixes.isEmpty()) continue
            replacements[entry.path] = patched.toByteArray(Charsets.UTF_8)
            scriptFixCount += fixes.size
            for (fix in fixes) {
                SessionLog.i(
                    "Script ${entry.path}: ${fix.rule}: '${fix.original}' -> '${fix.patched}'",
                )
            }
        }
        SessionLog.mark(
            "Install: script issue scan fixed $scriptFixCount call(s) " +
                "in ${SystemClock.elapsedRealtime() - scriptScanStarted}ms",
        )

        // Script dump (ground truth for on-device diagnosis): an index of every script and
        // scene the pack carries plus the full source of chat/scene scripts, written to
        // logs/scripts-dump.txt and included by LogExport. Chat-related sources are placed
        // last because LogExport keeps only the tail of large sections.
        runCatching {
            val dumpDir = File(context.filesDir, "logs")
            if (!dumpDir.exists()) dumpDir.mkdirs()
            val dumpFile = File(dumpDir, "scripts-dump.txt")
            val dump = StringBuilder()
            val scripts = pack.entries.filter { it.path.endsWith(".gd", true) && !it.isEncrypted && it.size in 1..MAX_CONFIG_BYTES }
            dump.appendLine("=== Script index (${scripts.size} scripts) ===")
            for (entry in scripts.sortedBy { it.path }) dump.appendLine("${entry.path} (${entry.size}B)")
            val scenes = pack.entries.filter { it.path.endsWith(".tscn", true) }
            dump.appendLine()
            dump.appendLine("=== Scene index (${scenes.size} scenes) ===")
            for (entry in scenes.sortedBy { it.path }) dump.appendLine("${entry.path} (${entry.size}B)")
            dump.appendLine()
            dump.appendLine("=== Sources ===")
            var sourceCount = 0
            for (entry in scripts.sortedBy { it.path }) {
                val path = entry.path
                val interesting = path.contains("chat", true) || path.contains("messenger", true) ||
                    path.contains("dialog", true) || path.contains("SceneView") ||
                    path.contains("ScreenNavigator") || path.endsWith("/Game.gd", true)
                if (!interesting) continue
                val source = runCatching { String(pack.readBytes(entry), Charsets.UTF_8) }.getOrNull() ?: continue
                dump.appendLine("---- $path ----")
                if (source.length > MAX_SCRIPT_DUMP_CHARS) {
                    dump.appendLine(source.takeLast(MAX_SCRIPT_DUMP_CHARS))
                    dump.appendLine("[... ${source.length - MAX_SCRIPT_DUMP_CHARS} earlier chars truncated ...]")
                } else {
                    dump.appendLine(source)
                }
                dump.appendLine()
                sourceCount++
            }
            dumpFile.writeText(dump.toString())
            SessionLog.mark("Install: script dump written ($sourceCount sources, ${dump.length} chars)")
        }.onFailure {
            SessionLog.w("Script dump failed: ${it.message}")
        }

        // Font diagnostics (log only, nothing is replaced): silent font failures show up in
        // game as invisible text and empty emoji glyphs without a single engine error. Verify
        // each font entry against the pack's own MD5 (proves the staged rewrite is innocent)
        // and sniff the header magic so a broken entry names itself in the exported log.
        val fontScanStarted = SystemClock.elapsedRealtime()
        var fontCount = 0
        var fontBadCount = 0
        for (entry in pack.entries) {
            val extension = entry.path.substringAfterLast('.', "").lowercase()
            if (extension !in FONT_EXTENSIONS) continue
            if (entry.isEncrypted || entry.size <= 0L) {
                SessionLog.w("Font ${entry.path}: unreadable (encrypted or ${entry.size} bytes)")
                fontBadCount++
                continue
            }
            // Color emoji fonts are multi-MB (the game's NotoColorEmoji is ~9MB compressed);
            // the generic 4MB read limit would throw and abort staging.
            if (entry.size > MAX_FONT_READ_BYTES) {
                SessionLog.w("Font ${entry.path}: skipped (too large to scan: ${entry.size} bytes)")
                continue
            }
            val data = runCatching { pack.readBytes(entry, MAX_FONT_READ_BYTES) }.getOrNull()
            if (data == null) {
                SessionLog.w("Font ${entry.path}: unreadable (read failed)")
                fontBadCount++
                continue
            }
            val integrityOk = md5(data).contentEquals(entry.md5)
            val format = fontFormat(data)
            fontCount++
            if (!integrityOk) fontBadCount++
            SessionLog.i(
                "Font ${entry.path}: ${data.size}B format=$format flags=${entry.flags} " +
                    "integrity=${if (integrityOk) "ok" else "MISMATCH"}",
            )
        }
        SessionLog.mark(
            "Install: font diagnostics: $fontCount font(s), $fontBadCount flagged " +
                "in ${SystemClock.elapsedRealtime() - fontScanStarted}ms",
        )

        val steamEntry = pack.entries.firstOrNull { it.path == STEAM_PLATFORM_SCRIPT }
        if (steamEntry != null && !steamEntry.isEncrypted && steamEntry.size <= MAX_CONFIG_BYTES) {
            val original = String(pack.readBytes(steamEntry), Charsets.UTF_8)
            val patched = STEAM_PLATFORM_STUB
            replacements[STEAM_PLATFORM_SCRIPT] = patched.toByteArray(Charsets.UTF_8)
            SessionLog.i("SteamPlatform.gd: replaced with stub (Steam GDExtension has no Android build)")
        }

        val patchedConfigs = plans.count { it.patched }

        onStage("Patching Android extensions", 0.68f)
        var addedFiles = 0
        if (replacements.isEmpty() && additions.isEmpty()) {
            if (target.exists()) target.delete()
            check(incoming.renameTo(target) || runCatching { incoming.copyTo(target, overwrite = true) }.isSuccess) {
                "Couldn't move the pack into place."
            }
            if (incoming.exists()) incoming.delete()
        } else {
            if (target.exists()) target.delete()
            val rewriteStarted = SystemClock.elapsedRealtime()
            val result = pack.rewriteInto(patched, replacements, additions) { written ->
                val fraction = if (copiedSize > 0L) (written.toFloat() / copiedSize).coerceIn(0f, 1f) else 0f
                onStage("Patching Android extensions", 0.68f + 0.27f * fraction)
            }
            SessionLog.mark("Install: pack rewrite in ${SystemClock.elapsedRealtime() - rewriteStarted}ms")
            check(patched.renameTo(target)) { "Couldn't finalise the patched pack." }
            incoming.delete()
            addedFiles = result.added.size
        }

        // A different pack has its own renderer setting; the remembered original is stale now.
        prefs.resetRendererOverride()

        // Pre-align for the currently selected backend so the first launch does not need to index.
        onStage("Aligning renderer", 0.95f)
        val alignStarted = SystemClock.elapsedRealtime()
        runCatching {
            ProjectSettingsOverride.prepare(context, target, prefs.backend)
        }.onFailure {
            SessionLog.w("Could not pre-align renderer for ${prefs.backend.label}: ${it.message}")
        }
        SessionLog.mark("Install: renderer alignment in ${SystemClock.elapsedRealtime() - alignStarted}ms")

        // Record the staged pack's cheap identity (size + lastModified) and keep the source
        // URI around so later launches can validate — and re-copy — without any hashing.
        prefs.recordStagedPack(RunnerPrefs.StagedPackInfo(target.length(), target.lastModified()))
        storeSourceUri(uri)

        onStage("Finishing up", 0.97f)
        SessionLog.i(
            "Install complete: ${target.length()} bytes, $patchedConfigs config(s) rewritten" +
                if (addedFiles > 0) ", $addedFiles file(s) added" else "",
        )
        Outcome(
            packFile = target,
            sourceName = sourceName,
            sizeBytes = target.length(),
            entryCount = pack.entries.size,
            plans = plans,
            patchedCount = patchedConfigs,
        )
    }

    private fun packFile(): File = File(workDirectory(), PACK_NAME)

    private fun workDirectory(): File = File(context.filesDir, WORK_DIRECTORY)

    private fun deviceAbiDirectory(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64-v8a"
        "armeabi-v7a", "armeabi" -> "armeabi-v7a"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> "arm64-v8a"
    }

    private fun contentSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    /** Keeps the content grant alive across process restarts so [ensureStaged] can re-copy. */
    private fun storeSourceUri(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { SessionLog.w("Source grant is not persistable; re-copying later may need a fresh pick") }
        prefs.storeSourceUri(uri.toString())
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun readJsonShim(): ByteArray? = runCatching {
        context.assets.open(JSON_SHIM_ASSET).use { it.readBytes() }
    }.getOrNull()

    companion object {
        private const val WORK_DIRECTORY = "game"
        private const val PACK_NAME = "game.pck"
        private const val INCOMING_NAME = "incoming.pck"
        private const val MAX_CONFIG_BYTES = 512L * 1024
        private const val COPY_BUFFER = 1 shl 20

        private const val STORE_SCRIPT = "res://Store.gd"
        private const val SCENE_VIEW_SCRIPT = "res://ui/components/Game/SceneView/SceneView.gd"

        private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc", "woff", "woff2", "fnt", "fontdata")
        private const val MAX_FONT_READ_BYTES = 64L * 1024 * 1024
        private const val MAX_SCRIPT_DUMP_CHARS = 80_000

        private fun fontFormat(data: ByteArray): String? {
            if (data.size < 4) return "empty"
            val magic = ((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            return when (magic) {
                0x00010000 -> "truetype"
                0x4F54544F -> "opentype"
                0x74746366 -> "ttc"
                0x774F4646 -> "woff"
                0x774F4632 -> "woff2"
                0x52535243 -> "godot-resource" // .fontdata (RSRC)
                0x52534343 -> "godot-resource-compressed" // .fontdata (RSCC)
                else -> "unknown"
            }
        }

        private fun md5(data: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("MD5").digest(data)
        private const val STEAM_PLATFORM_SCRIPT = "res://components/DistPlatform/SteamPlatform.gd"
        private const val STEAM_PLATFORM_STUB = """
extends "DistPlatform.gd"

const DLC_MAP = {
    "RedVelvet": 3902750,
}

var is_online: bool = false
var steam_id: int = 0
var is_owned: bool = false

func setup() -> bool:
    print("Steam not available on Android")
    return true

func get_dlc_id(name: String):
    return str(DLC_MAP[name])

func _process(delta):
    pass

func install_dlc_handler(f: Callable):
    pass

func get_owned_dlcs():
    return []

func is_dlc_owned(dlc_id: String):
    return false

func open_uri(uri: String):
    if uri.begins_with("steam://"):
        return
    OS.shell_open(uri)


func unlock_achievement(id: String):
    pass

func is_xss():
    return false

func get_username():
    return ""

func set_custom_state(state: String, scene: String):
    pass

func open_store_page(s):
    pass

func reboot():
    super.reboot()
"""
        private const val JSON_UTILS_DECLARATION = "JsonUtils.new()"
        private const val JSON_SHIM_PATH = "res://utar_natives/json_utils.gd"
        private const val JSON_SHIM_DECLARATION = "preload(\"res://utar_natives/json_utils.gd\").new()"
        private const val JSON_SHIM_ASSET = "json_utils.gd"
    }
}
