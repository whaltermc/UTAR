package com.utar.runner.core

import android.os.SystemClock
import android.system.Os
import com.utar.runner.runtime.DebugLog
import com.utar.runner.runtime.SessionLog
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.lang.reflect.Method

/**
 * Pre-warms the OS page cache for the staged pack while the launcher's "starting" screen is up,
 * so the engine process starts reading from RAM instead of cold storage. Runs on Dispatchers.IO
 * before the Godot activity is started.
 *
 * posix_fadvise(WILLNEED) asks the kernel to read the whole file ahead; FADV_SEQUENTIAL raises
 * the readahead window for the sequential pass the engine will do. android.system.Os only
 * exposes posix_fadvise on very new API levels, so it is reached through reflection; devices
 * without it fall back to a plain sequential read, which faults the pages in directly.
 */
object PackWarmup {

    private const val FADV_SEQUENTIAL = 2
    private const val FADV_WILLNEED = 3

    private val fadvise: Method? = runCatching {
        Os::class.java.getMethod(
            "posix_fadvise",
            FileDescriptor::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
    }.getOrNull()

    fun warm(pack: File) {
        if (!pack.isFile || pack.length() <= 0L) return
        val started = SystemClock.elapsedRealtime()
        try {
            RandomAccessFile(pack, "r").use { raf ->
                try {
                    adviseWillNeed(raf.fd)
                    DebugLog.timing(
                        "Page-cache warmup: fadvise WILLNEED issued for ${pack.length()} bytes " +
                            "in ${SystemClock.elapsedRealtime() - started}ms",
                    )
                } catch (adviceError: Throwable) {
                    DebugLog.timing(
                        "Page-cache warmup: fadvise unavailable (${adviceError.javaClass.simpleName}), " +
                            "reading sequentially",
                    )
                    sequentialRead(pack)
                    DebugLog.timing(
                        "Page-cache warmup: sequential read finished in " +
                            "${SystemClock.elapsedRealtime() - started}ms",
                    )
                }
            }
        } catch (error: Throwable) {
            // Warmup is an optimisation only; never let it block a launch.
            SessionLog.w("Page-cache warmup skipped: ${error.message}")
        }
    }

    /** FADV_SEQUENTIAL first (bigger readahead), then WILLNEED (start pulling pages in now). */
    private fun adviseWillNeed(fd: FileDescriptor) {
        val method = fadvise ?: throw UnsupportedOperationException("posix_fadvise is not exposed")
        method.invoke(null, fd, 0L, 0L, FADV_SEQUENTIAL)
        method.invoke(null, fd, 0L, 0L, FADV_WILLNEED)
    }

    private fun sequentialRead(pack: File) {
        pack.inputStream().use { input ->
            val buffer = ByteArray(READ_BUFFER)
            while (input.read(buffer) >= 0) {
                // Drain: the read itself is the point.
            }
        }
    }

    private const val READ_BUFFER = 1 shl 20
}
