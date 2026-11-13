package com.utar.runner.core

import android.content.Context
import android.os.SystemClock
import com.utar.runner.runtime.Backend
import com.utar.runner.runtime.RunnerPrefs
import com.utar.runner.runtime.SessionLog
import java.io.File
import java.io.RandomAccessFile

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
            // Pack keeps the engine default (no key stored). Auto/Vulkan are fine; GLES is not.
            if (backend != Backend.GLES) {
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
            Backend.GLES -> placeholderForLength(slot.valueLength)
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
        val bytes = value.toByteArray(charset = java.nio.charset.StandardCharsets.UTF_8)
        require(bytes.size == slot.valueLength) { "Replacement string length mismatch for ${slot.path}" }
        RandomAccessFile(pack, "rw").use { raf ->
            raf.seek(slot.valueOffset)
            raf.write(bytes, 0, bytes.size)
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
        RandomAccessFile(pack, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    }.getOrNull()

    /** Parses `res://project.binary` inside [pack] and locates the rendering_method string payload. */
    private fun locateMethodSlot(pack: File): MethodSlot? {
        val pck = PckPack.open(pack)
        val entry = pck.entries.find { it.path == "res://project.binary" } ?: return null
        val data = pck.readBytes(entry)

        var off = 8 // skip "ECFG" + u32 version
        while (off + 4 <= data.size) {
            val klen = readIntLE(data, off); off += 4
            if (klen < 1 || klen > 4096 || off + klen > data.size) return null
            val key = String(data, off, klen, java.nio.charset.StandardCharsets.UTF_8); off += klen
            if (off + 4 > data.size) return null
            val vsize = readIntLE(data, off); off += 4
            if (off + vsize > data.size) return null

            if (key == METHOD_KEY_MOBILE || key == METHOD_KEY) {
                val vtype = readIntLE(data, off)
                if (vtype == 4 && off + 8 <= off + vsize) {
                    val strLen = readIntLE(data, off + 4)
                    if (strLen >= 0 && strLen + 8 <= vsize) {
                        val current = String(data, off + 8, strLen, java.nio.charset.StandardCharsets.UTF_8)
                        val absValueOffset = entry.offset + off + 8
                        return MethodSlot(key, absValueOffset, strLen, current)
                    }
                }
            }
            off += vsize
        }
        return null
    }

    private fun readIntLE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)
}
