package com.utar.runner.core

import android.content.Context
import com.utar.runner.runtime.PckPatchLog
import com.utar.runner.runtime.GameLogCapture
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import com.utar.runner.runtime.Backend
import com.utar.runner.runtime.DebugLog
import com.utar.runner.runtime.RunnerPrefs
import com.utar.runner.runtime.SessionLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        PckPatchLog.init(context)
        val target = packFile()
        val record = prefs.stagedPackInfo()
        if (target.isFile && record != null &&
            target.length() == record.sizeBytes &&
            target.lastModified() == record.lastModifiedMs
        ) {
            PckPatchLog.mark("Staging: staged pack matches (size+mtime) — no copy needed")
            return@withContext target
        }

        PckPatchLog.mark("Staging: staged pack missing or changed — re-copying from source")
        val sourceUri = prefs.sourceUri()
            ?: throw IllegalStateException(
                "The installed game data is missing or changed. Choose your .pck again.",
            )
        install(Uri.parse(sourceUri), prefs.installRecord()?.fileName, onStage).packFile
    }

    fun clear() {
        PckPatchLog.init(context)
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
        PckPatchLog.init(context)
        val directory = workDirectory().apply { mkdirs() }
        val incoming = File(directory, INCOMING_NAME)
        val target = File(directory, PACK_NAME)
        val patched = File(directory, "$PACK_NAME.tmp")
        listOf(incoming, patched).forEach { if (it.exists()) it.delete() }

        val sourceName = displayName(uri) ?: fallbackName?.takeIf { it.isNotBlank() } ?: "game.pck"
        PckPatchLog.i("Install started: $sourceName ($uri)")

        onStage("Copying into private storage", 0.02f)
        val copyStarted = SystemClock.elapsedRealtime()
        val expectedSize = contentSize(uri)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        if (pfd != null) {
            val fd = pfd.detachFd()
            pfd.close()
            val copied = NativeCore.copyFile(
                fd,
                incoming.absolutePath,
                expectedSize ?: -1L,
                NativeCore.Progress { done, total ->
                    if (total > 0L) {
                        val fraction = (done.toFloat() / total).coerceIn(0f, 1f)
                        onStage("Copying into private storage", 0.02f + 0.60f * fraction)
                    }
                },
            )
            if (copied < 0L) throw IllegalStateException(NativeCore.lastError() ?: "Copy failed.")
        } else {
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
        }
        PckPatchLog.mark("Install: pack copied in ${SystemClock.elapsedRealtime() - copyStarted}ms")

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
        PckPatchLog.mark("Install: pack opened/indexed in ${SystemClock.elapsedRealtime() - packOpenStarted}ms")
        PckPatchLog.i(
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
        PckPatchLog.mark("Install: native library discovery in ${SystemClock.elapsedRealtime() - nativesScanStarted}ms")
        PckPatchLog.i(
            "Android extension libraries: " +
                natives.allLibraries.joinToString().ifEmpty { "none (drop builds into natives/android/<abi>/)" },
        )

        val plans = mutableListOf<GdextensionRewriter.Plan>()
        val replacements = LinkedHashMap<String, ByteArray>()
        val gdextStart = SystemClock.elapsedRealtime()
        for (entry in pack.entries) {
            if (!entry.path.endsWith(".gdextension", ignoreCase = true)) continue
            if (entry.isEncrypted || entry.size > MAX_CONFIG_BYTES) {
                PckPatchLog.w("Skipping unreadable extension config ${entry.path}")
                continue
            }
            val original = String(pack.readBytes(entry), Charsets.UTF_8)
            val plan = GdextensionRewriter.plan(entry.path, original, natives)
            plans += plan
            if (plan.patched) {
                replacements[entry.path] = plan.text.toByteArray(Charsets.UTF_8)
                PckPatchLog.i(
                    "Extension ${entry.path} -> " +
                        plan.libraries.entries.joinToString { "${it.key}/${it.value}" },
                )
            } else {
                PckPatchLog.w("No Android build matched ${entry.path}; it will fail to load on device")
            }
        }
        PckPatchLog.mark("Install: GDExtension matching for ${plans.size} config(s) in ${SystemClock.elapsedRealtime() - gdextStart}ms")

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
                    PckPatchLog.i("Store.gd: replaced the jsonutils GDExtension with the GDScript shim")
                } else {
                    PckPatchLog.w("jsonutils shim missing from APK assets; Store.gd will fail to load")
                }
            }
        }

        // Fix known GDScript issues in every script the pack carries — parse errors on the
        // target engine (e.g. load_threaded_request called with a 5th argument) that keep a
        // script from compiling. Over-argued calls are trimmed to the documented maximum.
        //
        // SceneView.gd (the scene-select page's button script) has the same 5-argument
        // load_threaded_request call. Left unpatched it fails to compile on 4.1.4, so the buttons never
        // get their unlock state, thumbnails or click handling and the whole page shows greyed out.
        // The old note here warned that patching it blacks out scenes, but that described a per-line
        // scene-art loader that this pack's SceneView.gd does not contain. Set PATCH_SCENE_VIEW to
        // false to go back to the old behaviour (then re-install the pack).
        val scriptScanStarted = SystemClock.elapsedRealtime()
        var scriptFixCount = 0
        for (entry in pack.entries) {
            if (!entry.path.endsWith(".gd", ignoreCase = true)) continue
            if (entry.path == SCENE_VIEW_SCRIPT && !PATCH_SCENE_VIEW) continue
            if (entry.isEncrypted || entry.size <= 0L || entry.size > MAX_CONFIG_BYTES) continue
            val original = String(pack.readBytes(entry), Charsets.UTF_8)
            val (patchedSource, fixes) = ScriptPatcher.fix(original)
            if (fixes.isEmpty()) continue
            replacements[entry.path] = patchedSource.toByteArray(Charsets.UTF_8)
            scriptFixCount += fixes.size
            for (fix in fixes) {
                PckPatchLog.i(
                    "Script ${entry.path}: ${fix.rule}: '${fix.original}' -> '${fix.patched}'",
                )
            }
        }
        PckPatchLog.mark(
            "Install: script issue scan fixed $scriptFixCount call(s) " +
                "in ${SystemClock.elapsedRealtime() - scriptScanStarted}ms",
        )

        // Script dump (ground truth for on-device diagnosis) runs natively.
        runCatching {
            val dumpDir = File(context.filesDir, "logs").apply { mkdirs() }
            val sourceCount = pack.scriptDump(dumpDir.absolutePath, MAX_SCRIPT_DUMP_CHARS)
            PckPatchLog.mark("Install: script dump written ($sourceCount sources)")
        }.onFailure {
            PckPatchLog.w("Script dump failed: ${it.message}")
        }

        // Font diagnostics (log only) run natively (heavy I/O + MD5 in JNI).
        val fontScanStarted = SystemClock.elapsedRealtime()
        val fontResult = pack.fontScan(MAX_FONT_READ_BYTES)
        val fontCount = (fontResult ushr 32).toInt()
        val fontBadCount = (fontResult and 0xFFFFFFFFL).toInt()
        PckPatchLog.mark(
            "Install: font diagnostics: $fontCount font(s), $fontBadCount flagged " +
                "in ${SystemClock.elapsedRealtime() - fontScanStarted}ms",
        )

        val steamEntry = pack.entries.firstOrNull { it.path == STEAM_PLATFORM_SCRIPT }
        if (steamEntry != null && !steamEntry.isEncrypted && steamEntry.size <= MAX_CONFIG_BYTES) {
            // The pack ships the DLC scenes when they are present; with no Steam to ask, report the
            // DLC as owned in that case (or when the "Force DLC unlock" switch is on).
            val packHasDlc = pack.entries.any { it.path.startsWith(DLC_SCENE_PREFIX) }
            val dlcOwned = packHasDlc || prefs.dlcOverride
            replacements[STEAM_PLATFORM_SCRIPT] = steamPlatformStub(dlcOwned).toByteArray(Charsets.UTF_8)
            PckPatchLog.i(
                "SteamPlatform.gd: replaced with stub (Steam GDExtension has no Android build); " +
                    "DLC reported as ${if (dlcOwned) "owned" else "not owned"} " +
                    "(pack has DLC scenes: $packHasDlc, override: ${prefs.dlcOverride})",
            )
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
            PckPatchLog.mark("Install: pack rewrite in ${SystemClock.elapsedRealtime() - rewriteStarted}ms")
            check(patched.renameTo(target)) { "Couldn't finalise the patched pack." }
            incoming.delete()
            addedFiles = result.added.size
        }

        // Extract font source files so Godot can open them with a real file descriptor at runtime.
        // Without this, Godot's !fd error breaks text rendering in 3D scenes (blackscreen).
        runCatching {
            val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
            val extracted = pack.extractFontFiles(fontsDir.absolutePath)
            PckPatchLog.mark("Install: extracted $extracted font file(s) to ${fontsDir.path}")

            // Patch .fontdata files to reference user://fonts/ so Godot finds the extracted fonts.
            fontsDir.walk().filter { it.name.endsWith(".fontdata", ignoreCase = true) }.forEach { fontDataFile ->
                runCatching {
                    val content = fontDataFile.readText(Charsets.UTF_8)
                    val sourceRegex = Regex("""source\s*=\s*"([^"]+)"""")
                    val patchedContent = sourceRegex.replace(content) { match ->
                        val originalPath = match.groupValues[1]
                        val fileName = originalPath.substringAfterLast('/').substringAfterLast('\\')
                        """source = "user://fonts/$fileName""""
                    }
                    if (patchedContent != content) {
                        fontDataFile.writeText(patchedContent, Charsets.UTF_8)
                        PckPatchLog.i("Patched font data: ${fontDataFile.name}")
                    }
                }.onFailure {
                    PckPatchLog.w("Couldn't patch ${fontDataFile.name}: ${it.message}")
                }
            }
        }.onFailure {
            PckPatchLog.w("Font extraction failed: ${it.message}")
        }

        // A different pack has its own renderer setting; the remembered original is stale now.
        prefs.resetRendererOverride()

        // Pre-align for the currently selected backend so the first launch does not need to index.
        onStage("Aligning renderer", 0.95f)
        val alignStarted = SystemClock.elapsedRealtime()
        runCatching {
            ProjectSettingsOverride.prepare(context, target, prefs.backend)
        }.onFailure {
            PckPatchLog.w("Could not pre-align renderer for ${prefs.backend.label}: ${it.message}")
        }
        PckPatchLog.mark("Install: renderer alignment in ${SystemClock.elapsedRealtime() - alignStarted}ms")

        // Record the staged pack's cheap identity (size + lastModified) and keep the source
        // URI around so later launches can validate — and re-copy — without any hashing.
        prefs.recordStagedPack(RunnerPrefs.StagedPackInfo(target.length(), target.lastModified()))
        storeSourceUri(uri)

        onStage("Finishing up", 0.97f)
        // Preserve Godot's engine log (godot.log) so it is included in log exports.
        GameLogCapture.preserveGodotLog(context.filesDir)
        PckPatchLog.i(
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
        }.onFailure { PckPatchLog.w("Source grant is not persistable; re-copying later may need a fresh pick") }
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
        private const val PATCH_SCENE_VIEW = true
        private const val SCENE_VIEW_SCRIPT = "res://ui/components/Game/SceneView/SceneView.gd"

        private const val MAX_FONT_READ_BYTES = 64L * 1024 * 1024
        private const val MAX_SCRIPT_DUMP_CHARS = 80_000

        private const val STEAM_PLATFORM_SCRIPT = "res://components/DistPlatform/SteamPlatform.gd"
        private const val DLC_SCENE_PREFIX = "res://scenes/_dlc_rdvt/"

        private fun steamPlatformStub(dlcOwned: Boolean): String =
            STEAM_PLATFORM_STUB_TEMPLATE.replace("%DLC_OWNED%", if (dlcOwned) "true" else "false")

        private const val STEAM_PLATFORM_STUB_TEMPLATE = """
extends "DistPlatform.gd"

const DLC_MAP = {
    "RedVelvet": 3902750,
}

var is_online: bool = false
var steam_id: int = 0
var is_owned: bool = %DLC_OWNED%

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
    if is_owned:
        var ids = []
        for key in DLC_MAP:
            ids.append(str(DLC_MAP[key]))
        return ids
    return []

func is_dlc_owned(dlc_id: String):
    return is_owned

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

/**
 * Rewrites a `.gdextension` config so the engine picks the Android build of the extension
 * instead of the desktop binary the game was exported with.
 *
 * Desktop exports only declare libraries such as `windows.x86_64 = "res://bin/libfoo.windows.x86_64.dll"`.
 * On Android no line matches, so the extension silently fails to load. We add `android.<arch>`
 * entries that point at a path inside the pack that deliberately does not exist: Godot 4.1's
 * `OS_Android::open_dynamic_library` then falls back to the bare library name, which the linker
 * resolves from the APK's native library directory populated from `natives/android/<abi>/`.
 *
 * Which native library satisfies a config is decided by its `entry_symbol` when the built
 * libraries expose it (the reliable signal), falling back to matching library names.
 *
 * The planning runs natively ([NativeCore.gdextPlan]) — the Kotlin side parses the result
 * blob back into a typed [Plan].
 */
object GdextensionRewriter {

    data class Plan(
        val configPath: String,
        val text: String,
        /** ABI directory (e.g. `arm64-v8a`) to the native library that will satisfy this config. */
        val libraries: Map<String, String>,
    ) {
        val patched: Boolean get() = libraries.isNotEmpty()
    }

    fun plan(configPath: String, original: String, natives: NativeLibrarySet): Plan {
        val blob = NativeCore.gdextPlan(configPath, original, natives.toBlob())
            ?: return Plan(configPath, original, emptyMap())

        val reader = NativeLibrarySet.BlobReader(blob)
        val status = reader.int()
        val text = reader.string()
        val libCount = reader.int()
        val libraries = LinkedHashMap<String, String>()
        repeat(libCount) {
            val abi = reader.string()
            val library = reader.string()
            libraries[abi] = library
        }
        return if (status != 0) Plan(configPath, text, libraries) else Plan(configPath, text, emptyMap())
    }
}

/**
 * JNI surface of `libutarcore` — the runner's native core. Every heavy, I/O bound or
 * byte-precision operation lives behind these calls; the Kotlin classes in this package
 * are thin, type-safe wrappers that translate between `File`/`ByteArray` and the blob
 * formats the native side speaks.
 *
 * The library is loaded eagerly: first through [System.loadLibrary] (device), falling back
 * to a filesystem search (JVM unit tests) so the same code path runs in CI.
 */
object NativeCore {

    const val LIBRARY_NAME = "utarcore"

    /** Progress callback: (done, total). Total is `-1` where unknown (e.g. pack rewrites). */
    fun interface Progress {
        fun onProgress(done: Long, total: Long)
    }

    init {
        load()
    }

    private fun load() {
        try {
            System.loadLibrary(LIBRARY_NAME)
            return
        } catch (_: UnsatisfiedLinkError) {
            // fall through to the filesystem search
        }
        val found = candidates().firstOrNull { it.isFile } ?: run {
            val paths = candidates().joinToString("\n  ") { it.absolutePath }
            throw UnsatisfiedLinkError("Couldn't load $LIBRARY_NAME. Searched:\n  $paths")
        }
        System.load(found.absolutePath)
    }

    private fun candidates(): List<File> {
        val roots = listOfNotNull(
            File("app/src/test/resources"),
            File("src/test/resources"),
            File("build/intermediates/cxx"),
            File("../app/src/test/resources"),
            File("../app/build/intermediates/cxx"),
        )
        val direct = roots.map { File(it, "lib$LIBRARY_NAME.so") }
        val searched = roots.filter { it.name == "cxx" }.flatMap { root ->
            buildList {
                root.walkTopDown().forEach { file ->
                    if (file.name == "lib$LIBRARY_NAME.so" && file.isFile) add(file)
                    if (size > 8) return@forEach // enough fallbacks; the walk is bounded
                }
            }
        }
        return direct + searched
    }

    // --- native library identity ------------------------------------------------

    /** Last error message from the native side, or null when the previous call succeeded. */
    external fun lastError(): String?

    // --- PCK engine ---------------------------------------------------------------

    /** Opens a pack. Returns a native handle (0 on failure), owned until [pckClose]. */
    external fun pckOpen(path: String): Long

    external fun pckClose(handle: Long)

    /** Index snapshot blob: versions + all entries. Null on failure. */
    external fun pckSnapshot(handle: Long): ByteArray?

    /** Reads one unencrypted entry. Null on failure (check [lastError]). */
    external fun pckReadEntry(handle: Long, index: Int, limit: Long): ByteArray?

    /** Index of the entry with [path], or -1. */
    external fun pckFindEntry(handle: Long, path: String): Int

    /** Streaming rewrite; returns bytes written or -1. */
    external fun pckRewrite(
        handle: Long,
        target: String,
        replPaths: Array<String>,
        replPayloads: Array<ByteArray>,
        addPaths: Array<String>,
        addPayloads: Array<ByteArray>,
        progress: Progress?,
    ): Long

    /** Writes a brand-new pack in the engine's format; true on success. */
    external fun pckWriteNew(
        target: String,
        paths: Array<String>,
        payloads: Array<ByteArray>,
        major: Int,
        minor: Int,
        patch: Int,
    ): Boolean

    // --- library scanning -----------------------------------------------------------

    /** APK library scan blob (NativeLibrarySet format); null when nothing usable is found. */
    external fun libScanApks(apks: Array<String>, abi: String, cacheDir: String?): ByteArray?

    /** Same scan against a directory of extracted libraries. */
    external fun libScanDirectory(directory: String?, abi: String): ByteArray?

    // --- gdextension rewriting --------------------------------------------------------

    /** GdextPlan blob for [original]; never null (unmatched configs return status 0). */
    external fun gdextPlan(configPath: String, original: String, libraryBlob: ByteArray): ByteArray?

    // --- script patching ----------------------------------------------------------------

    /** ScriptFixResult blob; null on internal failure (callers keep the source untouched). */
    external fun scriptFix(source: String): ByteArray?

    // --- project settings override --------------------------------------------------------

    /** RendererMethodSlot blob, or null when the pack keeps the engine default. */
    external fun settingsLocate(packPath: String): ByteArray?

    external fun settingsPatch(packPath: String, offset: Long, length: Int, value: String): Boolean

    // --- save backup ------------------------------------------------------------------------

    /** Returns bytes written, or -1. Consumes [fd] (the caller must detach it). */
    external fun saveBackup(root: String, fd: Int, progress: Progress?): Long

    /** Returns files restored, or -1. Consumes [fd]. */
    external fun saveRestore(fd: Int, root: String, progress: Progress?): Long

    // --- md5 ------------------------------------------------------------------------------------

    /** Computes MD5 of [data], returning 16 bytes. */
    external fun md5(data: ByteArray): ByteArray

    // --- font scan ------------------------------------------------------------------------------

    /** Scans font entries in the open pack; returns count in high 32 bits and bad count in low 32 bits. */
    external fun pckFontScan(handle: Long, maxBytes: Long): Long

    // --- script dump ----------------------------------------------------------------------------

    /** Writes logs/scripts-dump.txt for the open pack; returns source count or -1. */
    external fun scriptDump(handle: Long, logsDir: String, maxChars: Int): Int

    /** Extracts font source files and .fontdata from the open pack to [dstDir]; returns count or -1. */
    external fun extractFontFiles(handle: Long, dstDir: String): Int

    // --- file copy ------------------------------------------------------------------------------

    /** Copies [srcFd] (consumed) to [dstPath]; returns bytes copied or -1. */
    external fun copyFile(srcFd: Int, dstPath: String, expectedSize: Long, progress: Progress?): Long

    // --- pack warmup ---------------------------------------------------------------------------

    external fun warmup(path: String): Boolean

    // --- shader patch marker ----------------------------------------------------------------------

    /** Writes `filesDir/utar_patches/backend.txt`; the native loader only mounts patches when it says so. */
    external fun markerWrite(filesDir: String, value: String): Boolean

    /** Removes `filesDir/utar_patches` and `filesDir/shader_cache`. */
    external fun patcherClear(filesDir: String): Boolean
}

/**
 * The native libraries that the app ships for the current device ABI.
 *
 * Godot 4.1's Android loader opens `res://` gdextension libraries by their file name, which
 * resolves against the APK's native library directory. Depending on the AGP version those
 * libraries are either extracted into `nativeLibraryDir` or left inside the APK, so both
 * locations are supported.
 *
 * A thin wrapper over the native scanner ([NativeCore]): the APK ZIP walk, ELF64 dynamic
 * symbol extraction and the disk cache (NLSC v1, keyed by APK paths/sizes/mtimes + ABI) all
 * run in `libutarcore`. The Kotlin side only parses the compact result blob.
 */
class NativeLibrarySet internal constructor(
    /** ABI directory (e.g. `arm64-v8a`) to the libraries available in that directory. */
    val libraries: Map<String, List<NativeLibrary>>,
) {

    data class NativeLibrary(
        val fileName: String,
        /** Globals exported from the dynamic symbol table, e.g. `inkcpp_library_init`. */
        val entrySymbols: Set<String> = emptySet(),
    )

    val isEmpty: Boolean get() = libraries.isEmpty()

    val allLibraries: List<String> get() = libraries.values.flatten().map { it.fileName }.distinct()

    /** Serialises the set for the native gdextension planner (same layout as the NLSC payload). */
    internal fun toBlob(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u32(value: Int) {
            out.write(value)
            out.write(value shr 8)
            out.write(value shr 16)
            out.write(value shr 24)
        }
        fun str(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            u32(bytes.size)
            out.write(bytes)
        }
        u32(libraries.size)
        for ((abi, libs) in libraries) {
            str(abi)
            u32(libs.size)
            for (lib in libs) {
                str(lib.fileName)
                u32(lib.entrySymbols.size)
                for (symbol in lib.entrySymbols) {
                    str(symbol)
                }
            }
        }
        return out.toByteArray()
    }

    companion object {

        fun fromDirectory(directory: File?, abiDirectory: String): NativeLibrarySet {
            if (directory == null || !directory.isDirectory) return NativeLibrarySet(emptyMap())
            val blob = NativeCore.libScanDirectory(directory.absolutePath, abiDirectory)
                ?: return NativeLibrarySet(emptyMap())
            return fromBlob(blob)
        }

        /**
         * Reads the libraries AGP packaged into the installed APK and its splits. Current AGP
         * versions set `extractNativeLibs=false`, so the files never appear in
         * `nativeLibraryDir`; scanning the ZIP entries under `lib/<abi>/` is the
         * reliable route.
         */
        fun fromApks(apks: List<File>, abiDirectory: String): NativeLibrarySet {
            return fromApksCached(apks, abiDirectory, null)
        }

        /**
         * Same as [fromApks] but caches the ELF symbol scan results on disk so re-scanning
         * is skipped when the APKs and ABI are unchanged. [cacheDir] may be null, in which
         * case no disk cache is used.
         */
        fun fromApksCached(
            apks: List<File>,
            abiDirectory: String,
            cacheDir: File?,
        ): NativeLibrarySet {
            val paths = apks.filter { it.isFile }.map { it.absolutePath }.toTypedArray()
            val blob = NativeCore.libScanApks(paths, abiDirectory, cacheDir?.absolutePath)
                ?: return NativeLibrarySet(emptyMap())
            return fromBlob(blob)
        }

        /** Godot architecture feature tag for an Android ABI directory. */
        fun featureTag(abiDirectory: String): String = when (abiDirectory) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "arm32"
            "x86_64" -> "x86_64"
            "x86" -> "x86_32"
            else -> abiDirectory
        }

        /** Parses the native blob: u32 abiCount; per abi [len abi, u32 libCount, per lib [len name, u32 symCount, per sym [len sym]]]. */
        internal fun fromBlob(blob: ByteArray): NativeLibrarySet {
            val reader = BlobReader(blob)
            val abiCount = reader.int()
            val libraries = LinkedHashMap<String, List<NativeLibrary>>()
            repeat(abiCount) {
                val abi = reader.string()
                val libCount = reader.int()
                val libs = ArrayList<NativeLibrary>(libCount)
                repeat(libCount) {
                    val name = reader.string()
                    val symCount = reader.int()
                    val symbols = HashSet<String>(symCount)
                    repeat(symCount) { symbols += reader.string() }
                    libs += NativeLibrary(name, symbols)
                }
                libraries[abi] = libs
            }
            return NativeLibrarySet(libraries)
        }
    }

    /** Minimal little-endian reader over native result blobs. */
    internal class BlobReader(private val data: ByteArray) {
        private var at = 0

        fun int(): Int {
            check(at + 4 <= data.size) { "Truncated native result blob." }
            val value = (data[at].toInt() and 0xFF) or
                ((data[at + 1].toInt() and 0xFF) shl 8) or
                ((data[at + 2].toInt() and 0xFF) shl 16) or
                ((data[at + 3].toInt() and 0xFF) shl 24)
            at += 4
            return value
        }

        fun string(): String {
            val length = int()
            check(at + length <= data.size) { "Truncated native result blob." }
            val text = String(data, at, length, Charsets.UTF_8)
            at += length
            return text
        }
    }
}

/**
 * Pre-warms the OS page cache for the staged pack while the launcher's "starting" screen is up,
 * so the engine process starts reading from RAM instead of cold storage. Runs on Dispatchers.IO
 * before the Godot activity is started.
 *
 * The fadvise(SEQUENTIAL + WILLNEED) call and the sequential-read fallback both live natively
 * ([NativeCore.warmup]) — one syscall pair instead of reflection, with the same device
 * coverage (fadvise where available, plain reads everywhere else).
 */
object PackWarmup {

    fun warm(pack: File) {
        if (!pack.isFile || pack.length() <= 0L) return
        val started = SystemClock.elapsedRealtime()
        try {
            NativeCore.warmup(pack.absolutePath)
            DebugLog.timing(
                "Page-cache warmup: issued for ${pack.length()} bytes " +
                    "in ${SystemClock.elapsedRealtime() - started}ms",
            )
        } catch (error: Throwable) {
            // Warmup is an optimisation only; never let it block a launch.
            SessionLog.w("Page-cache warmup skipped: ${error.message}")
        }
    }
}

/**
 * Reader/writer for Godot 4.1 `.pck` archives (pack format version 2).
 *
 * A thin, type-safe wrapper over the native core ([NativeCore]): index parsing, entry reads
 * and the streaming rewrite run in `libutarcore` without JVM byte-buffer churn, which is
 * what keeps install/launch I/O fast on multi-gigabyte packs. [open] parses the index once
 * and keeps a native handle for entry reads; the native side also maintains its own
 * size/mtime-validated sidecar cache so re-opening a big pack does not re-parse its index.
 *
 * On-disk layout (handled natively):
 * ```
 * header  : magic "GDPC" | version | major | minor | patch | flags | file_base | 16 * u32 reserved
 * index   : entry count, then per entry: padded path length | path | offset | size | md5 | flags
 * padding : zero bytes up to file_base
 * data    : file payloads, 32-byte aligned, offsets stored relative to file_base
 * ```
 */
class PckPack private constructor(
    val file: File,
    val packVersion: Int,
    val engineMajor: Int,
    val engineMinor: Int,
    val enginePatch: Int,
    val entries: List<Entry>,
    private val handle: Long,
) : AutoCloseable {

    val engineVersion: String get() = "$engineMajor.$engineMinor.$enginePatch"

    private val indexByPath: Map<String, Int> = entries.mapIndexed { index, entry -> entry.path to index }.toMap()

    data class Entry(
        val path: String,
        val offset: Long,
        val size: Long,
        val md5: ByteArray,
        val flags: Int,
    ) {
        val name: String get() = path.substringAfterLast('/')
        val isEncrypted: Boolean get() = (flags and PACK_FILE_ENCRYPTED) != 0
    }

    data class RewriteResult(
        val replaced: List<String>,
        val added: List<String>,
        val bytesWritten: Long,
    )

    /** Reads a single (small, unencrypted) entry into memory, e.g. a `.gdextension` text file. */
    fun readBytes(entry: Entry, limit: Long = DEFAULT_READ_LIMIT): ByteArray {
        require(!entry.isEncrypted) { "${entry.path} is encrypted" }
        require(entry.size <= limit) { "${entry.path} is too large to load (${entry.size} bytes)" }
        val index = indexByPath[entry.path]
            ?: throw IllegalStateException("Entry is not part of this pack: ${entry.path}")
        return NativeCore.pckReadEntry(handle, index, limit)
            ?: throw IllegalStateException(NativeCore.lastError() ?: "Couldn't read ${entry.path}")
    }

    /**
     * Writes a copy of this pack to [target], replacing the payload of the entries named in
     * [replacements] and appending the files named in [additions] that the pack does not
     * already contain. Every untouched entry is copied byte for byte, keeping its recorded MD5.
     *
     * MD5 hashes are trusted metadata for unmodified entries (Godot only verifies them for
     * encrypted payloads) but are recomputed for anything we rewrite or add.
     */
    fun rewriteInto(
        target: File,
        replacements: Map<String, ByteArray>,
        additions: Map<String, ByteArray> = emptyMap(),
        onProgress: ((writtenBytes: Long) -> Unit)? = null,
    ): RewriteResult {
        require(target.absolutePath != file.absolutePath) { "Refusing to overwrite the source pack." }
        val knownPaths = entries.mapTo(HashSet()) { it.path }
        val unknown = replacements.keys - knownPaths
        require(unknown.isEmpty()) { "Pack has no entries named ${unknown.joinToString()}" }

        val effectiveReplacements = LinkedHashMap<String, ByteArray>(replacements)
        val newFiles = LinkedHashMap<String, ByteArray>()
        for ((path, payload) in additions) {
            if (path in knownPaths) effectiveReplacements[path] = payload else newFiles[path] = payload
        }

        val written = NativeCore.pckRewrite(
            handle,
            target.absolutePath,
            effectiveReplacements.keys.toTypedArray(),
            effectiveReplacements.values.toTypedArray(),
            newFiles.keys.toTypedArray(),
            newFiles.values.toTypedArray(),
            onProgress?.let { callback ->
                NativeCore.Progress { done, _ -> callback(done) }
            },
        )
        check(written >= 0) { NativeCore.lastError() ?: "Pack rewrite failed." }

        return RewriteResult(effectiveReplacements.keys.toList(), newFiles.keys.toList(), target.length())
    }

    /** Native font diagnostics scan (heavy I/O + MD5) run in JNI. */
    fun fontScan(maxBytes: Long): Long = NativeCore.pckFontScan(handle, maxBytes)

    /** Native script dump writer (heavy I/O) run in JNI. */
    fun scriptDump(logsDir: String, maxChars: Int): Int = NativeCore.scriptDump(handle, logsDir, maxChars)

    /** Extracts font source files and .fontdata from the pack to [dstDir]; returns count or -1. */
    fun extractFontFiles(dstDir: String): Int = NativeCore.extractFontFiles(handle, dstDir)

    /** Releases the native handle; further reads fail. Called automatically by the finalizer. */
    override fun close() {
        if (handle != 0L) {
            NativeCore.pckClose(handle)
        }
    }


    companion object {
        const val MAGIC = 0x43504447 // "GDPC"
        const val SUPPORTED_PACK_VERSION = 2
        const val PACK_FILE_ENCRYPTED = 1
        const val PACK_DIR_ENCRYPTED = 1

        private const val DEFAULT_READ_LIMIT = 4L * 1024 * 1024

        fun open(file: File): PckPack {
            require(file.isFile) { "Pack not found: ${file.name}" }
            val handle = NativeCore.pckOpen(file.absolutePath)
            require(handle != 0L) { NativeCore.lastError() ?: "Couldn't open ${file.name}" }
            val snapshot = NativeCore.pckSnapshot(handle)
            require(snapshot != null) { NativeCore.lastError() ?: "Couldn't read the pack index." }
            return fromSnapshot(file, handle, snapshot)
        }

        /** Snapshot blob: u32 versions, u32 count, per entry [len path, u64 offset, u64 size, 16 md5, u32 flags]. */
        private fun fromSnapshot(file: File, handle: Long, snapshot: ByteArray): PckPack {
            val buffer = ByteBufferReader(snapshot)
            val version = buffer.int()
            val major = buffer.int()
            val minor = buffer.int()
            val patch = buffer.int()
            val count = buffer.int()
            val entries = ArrayList<PckPack.Entry>(count)
            repeat(count) {
                val pathLength = buffer.int()
                val path = buffer.string(pathLength)
                val offset = buffer.long()
                val size = buffer.long()
                val md5 = buffer.bytes(16)
                val flags = buffer.int()
                entries += PckPack.Entry(path, offset, size, md5, flags)
            }
            return PckPack(file, version, major, minor, patch, entries, handle)
        }
    }

    /** Minimal little-endian reader over the native snapshot blob. */
    private class ByteBufferReader(private val data: ByteArray) {
        private var at = 0

        fun int(): Int {
            val value = (data[at].toInt() and 0xFF) or
                ((data[at + 1].toInt() and 0xFF) shl 8) or
                ((data[at + 2].toInt() and 0xFF) shl 16) or
                ((data[at + 3].toInt() and 0xFF) shl 24)
            at += 4
            return value
        }

        fun long(): Long {
            var value = 0L
            repeat(8) { shift -> value = value or ((data[at + shift].toLong() and 0xFF) shl (shift * 8)) }
            at += 8
            return value
        }

        fun string(length: Int): String {
            val text = String(data, at, length, Charsets.UTF_8)
            at += length
            return text
        }

        fun bytes(length: Int): ByteArray {
            val chunk = data.copyOfRange(at, at + length)
            at += length
            return chunk
        }
    }
}

/**
 * Aligns the game pack's `rendering/renderer/rendering_method` project setting with the backend the
 * user picked.
 *
 * Godot 4.1's Android host picks a Vulkan Java surface view (`GodotVulkanRenderView` + `VkThread`)
 * whenever the pack's project settings say `forward_plus`/`mobile` + Vulkan — *regardless* of the
 * `--rendering-method` engine flag. Forcing GLES then runs the native OpenGL renderer on the Vulkan
 * `VkThread`, which has no EGL context, so GL limits read 0, shader compilation fails and the engine
 * crashes with SIGSEGV in `VkThread`.
 *
 * We therefore patch the small `res://project.binary` entry in the installed pack in place: the renderer
 * method string is overwritten with a same-length placeholder so the Java host creates a GL surface view
 * that matches the native `--rendering-driver opengl3` driver. Because only the string bytes are
 * rewritten (length and all pack offsets are unchanged), this is safe and cheap on the multi-gigabyte
 * pack. The original value is restored when switching back to Vulkan/Auto.
 *
 * The location of the string (offset + length) is cached in prefs after the first locate, so later
 * launches never open or index the pack just to flip a few bytes.
 */
object ProjectSettingsOverride {

    private const val METHOD_KEY = "rendering/renderer/rendering_method"
    private const val METHOD_KEY_MOBILE = "$METHOD_KEY.mobile"

    /** 12-byte placeholder that is neither `forward_plus` nor `mobile`, so `usesVulkan()` returns false. */
    private const val VALUE_PLACEHOLDER_12 = "gl_compatibl"
    /** 6-byte placeholder that is neither `forward_plus` nor `mobile`. */
    private const val VALUE_PLACEHOLDER_6 = "gles3 "

    data class MethodSlot(
        val path: String,
        val valueOffset: Long,   // absolute file offset of the string payload
        val valueLength: Int,
        val currentValue: String,
    )

    /**
     * Patches the installed pack so the Java host view matches [backend]. Returns false when the pack's
     * renderer-method value can't be same-length patched (e.g. an unexpected length), in which case the
     * caller should tell the user that backend isn't available for this package.
     */
    fun prepare(context: Context, pack: File, backend: Backend): Boolean {
        val prepareStarted = SystemClock.elapsedRealtime()
        val prefs = RunnerPrefs(context)

        // Nothing to do when the pack is exactly as we last left it for this backend.
        if (prefs.rendererOverrideStamp == stamp(pack, backend)) {
            SessionLog.mark("ProjectSettingsOverride: stamp matches, skipping alignment (${backend.name})")
            return true
        }

        SessionLog.mark("ProjectSettingsOverride: resolving renderer slot for ${backend.name}")
        val slot = resolveSlot(prefs, pack) ?: run {
            // Pack keeps the engine default (no key stored). Vulkan is fine; GLES3 is not.
            if (backend != Backend.COMPATIBILITY) {
                prefs.rendererOverrideStamp = stamp(pack, backend)
                SessionLog.mark("ProjectSettingsOverride: no renderer_method key, ${backend.name} needs no patch")
                return true
            }
            return false
        }

        val stored = prefs.renderingMethodOriginal()
        val original = stored ?: slot.currentValue.takeIf { !isPlaceholder(it) }?.also {
            prefs.storeRenderingMethodOriginal(it)
        }

        val desired = when (backend) {
            Backend.COMPATIBILITY -> placeholderForLength(slot.valueLength)
            else -> original
        }?.takeIf { it.length == slot.valueLength } ?: return false

        if (desired != slot.currentValue) {
            SessionLog.mark("ProjectSettingsOverride: writing renderer method '${desired}' at offset ${slot.valueOffset}")
            writeString(pack, slot, desired)
        }
        prefs.rendererOverrideStamp = stamp(pack, backend)
        SessionLog.mark("ProjectSettingsOverride: alignment complete in ${SystemClock.elapsedRealtime() - prepareStarted}ms")
        return true
    }

    private fun stamp(pack: File, backend: Backend): String =
        "${backend.name}|${pack.length()}|${pack.lastModified()}"

    private fun isPlaceholder(value: String): Boolean =
        value == VALUE_PLACEHOLDER_12 || value == VALUE_PLACEHOLDER_6

    private fun placeholderForLength(length: Int): String? = when (length) {
        12 -> VALUE_PLACEHOLDER_12
        6 -> VALUE_PLACEHOLDER_6
        else -> null
    }

    private fun writeString(pack: File, slot: MethodSlot, value: String) {
        require(value.length == slot.valueLength) { "Replacement string length mismatch for ${slot.path}" }
        check(NativeCore.settingsPatch(pack.absolutePath, slot.valueOffset, slot.valueLength, value)) {
            NativeCore.lastError() ?: "Couldn't patch the renderer setting."
        }
    }

    /**
     * Prefer the cached offset/length so we never re-index a multi-GB pack on later launches.
     * Falls back to a one-time locate (and then caches the result).
     */
    private fun resolveSlot(prefs: RunnerPrefs, pack: File): MethodSlot? {
        val resolveStarted = SystemClock.elapsedRealtime()
        val cached = prefs.rendererSlot()
        if (cached != null) {
            val current = readStringAt(pack, cached.valueOffset, cached.valueLength) ?: return null
            SessionLog.mark("ProjectSettingsOverride: cached renderer slot hit (offset=${cached.valueOffset}, len=${cached.valueLength}) in ${SystemClock.elapsedRealtime() - resolveStarted}ms")
            return MethodSlot(cached.path, cached.valueOffset, cached.valueLength, current)
        }

        val located = locateMethodSlot(pack) ?: return null
        prefs.storeRendererSlot(
            RunnerPrefs.RendererSlot(located.path, located.valueOffset, located.valueLength),
        )
        SessionLog.mark("ProjectSettingsOverride: renderer slot located (offset=${located.valueOffset}, len=${located.valueLength}) in ${SystemClock.elapsedRealtime() - resolveStarted}ms")
        return located
    }

    private fun readStringAt(pack: File, offset: Long, length: Int): String? = runCatching {
        val bytes = ByteArray(length)
        java.io.FileInputStream(pack).use { stream ->
            stream.channel.position(offset)
            var done = 0
            while (done < length) {
                val read = stream.read(bytes, done, length - done)
                if (read <= 0) break
                done += read
            }
        }
        String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    }.getOrNull()

    /**
     * Parses `res://project.binary` inside [pack] and locates the rendering_method string
     * payload. Runs natively ([NativeCore.settingsLocate]); returns null when the pack keeps
     * the engine default (the key is simply not stored).
     */
    private fun locateMethodSlot(pack: File): MethodSlot? {
        val blob = NativeCore.settingsLocate(pack.absolutePath) ?: return null

        // Blob: [len path, path]; u64 valueOffset; u32 valueLength; [len currentValue, value].
        val reader = NativeLibrarySet.BlobReader(blob)
        val path = reader.string()
        val offset = (reader.int().toLong() and 0xFFFFFFFFL) or (reader.int().toLong() shl 32)
        val length = reader.int()
        val current = reader.string()
        return MethodSlot(path, offset, length, current)
    }
}

/**
 * Backs up and restores the game's save file (`user://savegame.res`), which is the only
 * file the game treats as user progress. The staged pack, logs and shader cache are never
 * touched — they are either re-copyable via the install flow or regenerable.
 */
class SaveBackup(private val context: Context) {

    private val saveFile: File get() = File(context.filesDir, "savegame.res")

    suspend fun backup(target: Uri, onStage: (stage: String, progress: Float) -> Unit): Long =
        withContext(Dispatchers.IO) {
            if (!saveFile.isFile) throw IllegalStateException("No save file to back up.")
            onStage("Backing up save", 0.1f)
            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: throw IllegalStateException("Couldn't open the backup destination.")
            var written = 0L
            saveFile.inputStream().use { input ->
                output.use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                    }
                }
            }
            SessionLog.i("Save backup complete: $written bytes")
            onStage("Backing up save", 1f)
            written
        }

    suspend fun restore(source: Uri, onStage: (stage: String, progress: Float) -> Unit): Int =
        withContext(Dispatchers.IO) {
            onStage("Restoring save", 0.1f)
            val input = context.contentResolver.openInputStream(source)
                ?: throw IllegalStateException("Couldn't read the backup file.")
            saveFile.parentFile?.mkdirs()
            var written = 0L
            input.use { inp ->
                saveFile.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = inp.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                    }
                }
            }
            SessionLog.i("Save restore complete: $written bytes")
            onStage("Restoring save", 1f)
            if (written == 0L) 0 else 1
        }
}

/**
 * Finds and fixes known GDScript API mismatches inside game scripts at install time.
 *
 * Each rule describes an engine function whose argument list the exported game overfills —
 * a parse error on the target Godot build that keeps the script from ever compiling (the
 * `load_threaded_request` 5-argument issue). A call taking more arguments than the rule
 * allows is trimmed back to the documented maximum, keeping the call itself intact; calls
 * that already fit are left byte-for-byte untouched. Every applied fix is reported so the
 * install log shows exactly what changed.
 *
 * Argument splitting respects nested (), [], {}, and "..." / '...' strings so that commas
 * inside nested calls or string literals are not mistaken for arg separators.
 *
 * The matching and rewriting runs natively ([NativeCore.scriptFix]); the Kotlin side parses
 * the result blob back into the typed view below.
 */
object ScriptPatcher {

    /** A single applied fix, reported for the install log. */
    data class Fix(val rule: String, val original: String, val patched: String)

    /** Rewrites [source], fixing every known issue. Returns the fixed source plus applied fixes. */
    fun fix(source: String): Pair<String, List<Fix>> {
        if (source.isEmpty()) return source to emptyList()
        val blob = NativeCore.scriptFix(source) ?: return source to emptyList()

        // Blob: u32 fixCount; per fix [len rule, len original, len patched]; [len text, text].
        val reader = NativeLibrarySet.BlobReader(blob)
        val fixCount = reader.int()
        val fixes = ArrayList<Fix>(fixCount)
        repeat(fixCount) {
            val rule = reader.string()
            val original = reader.string()
            val patched = reader.string()
            fixes += Fix(rule, original, patched)
        }
        val patchedSource = reader.string()
        return patchedSource to fixes
    }

    /** Backwards-compatible view over [fix]: (original call, patched call) pairs. */
    fun trimLoadThreadedRequestArgs(source: String): Pair<String, List<Pair<String, String>>> {
        val (patched, fixes) = fix(source)
        return patched to fixes.map { it.original to it.patched }
    }
}

/**
 * Android-side coordinator for the native shader patch pipeline.
 *
 * All of the GLES3 shader detection, rewriting, caching and override-pack generation lives in
 * C++ inside `libinternal.so` (see `natives/utinternal/src/patch_loader.cpp`) because it needs
 * the engine's own APIs — `DirAccess` over the mounted pack, `FileAccess::get_md5` and
 * `ProjectSettings.load_resource_pack(..., replace_files=true)` for patch priority. That side
 * runs inside the engine process at extension initialization, before any game resource loads.
 *
 * The only thing Kotlin must do is publish the manual backend selection as the marker file the
 * native loader reads (`user://utar_patches/backend.txt`), and clear the native caches when the
 * install is removed. When Forward+/Vulkan is selected the marker simply tells the native loader
 * to keep the original shaders untouched and mount nothing.
 */
object ShaderPatcher {

    /** Marker path, mirrored by the native loader ("user://utar_patches/backend.txt"). */
    const val MARKER_FILE = "utar_patches/backend.txt"

    fun prepare(context: Context, backend: Backend) {
        NativeCore.markerWrite(context.filesDir.absolutePath, backend.nativeMarker)
    }

    /** Removes the native patch pack and shader caches (used when clearing installs). */
    fun clear(context: Context) {
        NativeCore.patcherClear(context.filesDir.absolutePath)
    }
}
