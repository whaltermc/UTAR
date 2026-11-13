package com.utar.runner.core

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * The native libraries that the app ships for the current device ABI.
 *
 * Godot 4.1's Android loader opens `res://` gdextension libraries by their file name, which
 * resolves against the APK's native library directory. Depending on the AGP version those
 * libraries are either extracted into `nativeLibraryDir` or left inside the APK, so both
 * locations are supported.
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

    companion object {
        /**
         * Libraries that belong to the engine or its runtime dependencies rather than to a
         * game extension. FMOD's core/studio libraries are packaged next to the extension
         * that links them, so they must not be offered as GDExtension candidates.
         */
        private val ENGINE_LIBRARIES = setOf(
            "libgodot_android.so",
            "libc++_shared.so",
            "libc++abi.so",
            "libandroid_support.so",
            "libmain.so",
            "libfmod.so",
            "libfmodstudio.so",
        )

        private const val SHT_DYNSYM = 11
        private const val ELF64_HEADER_SIZE = 64
        private const val ELF64_SECTION_SIZE = 64
        private const val ELF64_SYMBOL_SIZE = 24
        private const val MAX_SECTION_BYTES = 8L * 1024 * 1024
        private const val MAX_SECTION_TABLE_BYTES = 8L * 1024 * 1024

        fun fromDirectory(directory: File?, abiDirectory: String): NativeLibrarySet {
            if (directory == null || !directory.isDirectory) return NativeLibrarySet(emptyMap())
            val libraries = directory.listFiles { candidate ->
                candidate.isFile && candidate.name.endsWith(".so") && candidate.name !in ENGINE_LIBRARIES
            }?.sortedBy { it.name }?.map { NativeLibrary(it.name, exportedSymbols(it)) }.orEmpty()
            if (libraries.isEmpty()) return NativeLibrarySet(emptyMap())
            return NativeLibrarySet(mapOf(abiDirectory to libraries))
        }

        /**
         * Reads the libraries AGP packaged into the installed APK and its splits. Current AGP
         * versions set `extractNativeLibs=false`, so the files never appear in
         * `nativeLibraryDir`; scanning the ZIP entry names under `lib/<abi>/` is the
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
            val cache = cacheDir?.let { NativeLibraryCache.forApks(it, apks, abiDirectory) }
            val cached = cache?.load()
            if (cached != null) {
                return NativeLibrarySet(cached.libraries)
            }

            val result = scanApks(apks, abiDirectory)
            cache?.save(result.libraries)
            return result
        }

        private fun scanApks(apks: List<File>, abiDirectory: String): NativeLibrarySet {
            val libraries = LinkedHashMap<String, NativeLibrary>()
            for (apk in apks) {
                if (!apk.isFile) continue
                runCatching {
                    ZipFile(apk).use { zip ->
                        val prefix = "lib/$abiDirectory/"
                        zip.entries().asSequence()
                            .filter { entry ->
                                !entry.isDirectory && entry.name.startsWith(prefix) &&
                                    entry.name.endsWith(".so")
                            }
                            .sortedBy { it.name }
                            .forEach { entry ->
                                val fileName = entry.name.substringAfterLast('/')
                                if (fileName in ENGINE_LIBRARIES || fileName in libraries) return@forEach
                                val data = runCatching {
                                    zip.getInputStream(entry).use { it.readBytes() }
                                }.getOrNull() ?: return@forEach
                                libraries[fileName] = NativeLibrary(fileName, exportedSymbols(data))
                            }
                    }
                }
            }
            if (libraries.isEmpty()) return NativeLibrarySet(emptyMap())
            return NativeLibrarySet(mapOf(abiDirectory to libraries.values.toList()))
        }

        /** Godot architecture feature tag for an Android ABI directory. */
        fun featureTag(abiDirectory: String): String = when (abiDirectory) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "arm32"
            "x86_64" -> "x86_64"
            "x86" -> "x86_32"
            else -> abiDirectory
        }

        /**
         * Reads the global symbols a shared library defines. This is how a `.gdextension`'s
         * `entry_symbol` is matched to the library that actually implements it, which stays
         * correct even when the built file name differs from the game's desktop binary.
         */
        internal fun exportedSymbols(file: File): Set<String> = runCatching {
            RandomAccessFile(file, "r").use { handle ->
                symbolsFrom(handle.length()) { offset, size ->
                    runCatching {
                        val data = ByteArray(size)
                        handle.seek(offset)
                        handle.readFully(data)
                        data
                    }.getOrNull()
                }
            }
        }.getOrDefault(emptySet())

        internal fun exportedSymbols(data: ByteArray): Set<String> = runCatching {
            symbolsFrom(data.size.toLong()) { offset, size ->
                val start = offset.toInt()
                if (start < 0 || size < 0 || start.toLong() + size.toLong() > data.size.toLong()) {
                    null
                } else {
                    data.copyOfRange(start, start + size)
                }
            }
        }.getOrDefault(emptySet())

        private fun symbolsFrom(
            length: Long,
            read: (offset: Long, size: Int) -> ByteArray?,
        ): Set<String> {
            if (length < ELF64_HEADER_SIZE) return emptySet()
            val header = read(0L, ELF64_HEADER_SIZE) ?: return emptySet()
            if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
            ) {
                return emptySet()
            }
            // Only 64-bit little-endian libraries are packaged (arm64-v8a / x86_64).
            if (header[4].toInt() != 2 || header[5].toInt() != 1) return emptySet()

            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val sectionTableOffset = buffer.getLong(0x28)
            val sectionEntrySize = buffer.getShort(0x3A).toInt() and 0xFFFF
            val sectionCount = buffer.getShort(0x3C).toInt() and 0xFFFF
            if (sectionTableOffset <= 0L || sectionEntrySize < ELF64_SECTION_SIZE || sectionCount <= 0) {
                return emptySet()
            }

            val tableSize = sectionEntrySize.toLong() * sectionCount
            if (tableSize > MAX_SECTION_TABLE_BYTES) return emptySet()
            val table = read(sectionTableOffset, tableSize.toInt()) ?: return emptySet()
            val sections = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)

            fun sectionOffset(index: Int) = sections.getLong(index * sectionEntrySize + 24)
            fun sectionSize(index: Int) = sections.getLong(index * sectionEntrySize + 32)
            fun sectionLink(index: Int) = sections.getInt(index * sectionEntrySize + 40)

            fun readSection(index: Int): ByteArray? {
                val offset = sectionOffset(index)
                val size = sectionSize(index)
                if (offset <= 0L || size <= 0L || size > MAX_SECTION_BYTES) return null
                return read(offset, size.toInt())
            }

            val symbols = HashSet<String>()
            for (index in 0 until sectionCount) {
                if (sections.getInt(index * sectionEntrySize + 4) != SHT_DYNSYM) continue
                val names = readSection(sectionLink(index)) ?: continue
                val entries = readSection(index) ?: continue
                if (names.isEmpty() || entries.isEmpty()) continue
                val cursor = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN)
                var position = 0
                while (position + ELF64_SYMBOL_SIZE <= entries.size) {
                    val nameOffset = cursor.getInt(position)
                    val defined = cursor.getShort(position + 6).toInt() != 0 // not SHN_UNDEF
                    if (defined && nameOffset in 0 until names.size) {
                        val name = cString(names, nameOffset)
                        if (name.isNotEmpty()) symbols += name
                    }
                    position += ELF64_SYMBOL_SIZE
                }
            }
            return symbols
        }

        private fun cString(table: ByteArray, offset: Int): String {
            var end = offset
            while (end < table.size && table[end] != 0.toByte()) end++
            return String(table, offset, end - offset, Charsets.UTF_8)
        }

        class LeReader(private val input: InputStream) {

            private val buffer = ByteArray(256 * 1024)
            private var position = 0
            private var limit = 0

            private fun fill(): Boolean {
                val read = input.read(buffer, 0, buffer.size)
                if (read <= 0) return false
                position = 0
                limit = read
                return true
            }

            fun u32(): Long {
                if (limit - position >= 4) {
                    val b = buffer
                    val p = position
                    position = p + 4
                    return (b[p].toLong() and 0xFF) or
                        ((b[p + 1].toLong() and 0xFF) shl 8) or
                        ((b[p + 2].toLong() and 0xFF) shl 16) or
                        ((b[p + 3].toLong() and 0xFF) shl 24)
                }
                var value = 0L
                repeat(4) { shift -> value = value or (byte().toLong() shl (shift * 8)) }
                return value
            }

            fun u64(): Long {
                if (limit - position >= 8) {
                    val low = u32()
                    val high = u32()
                    return low or (high shl 32)
                }
                var value = 0L
                repeat(8) { shift -> value = value or (byte().toLong() shl (shift * 8)) }
                return value
            }

            fun bytes(length: Int): ByteArray {
                val data = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    if (position >= limit && !fill()) throw EOFException("Unexpected end of data.")
                    val chunk = minOf(length - offset, limit - position)
                    System.arraycopy(buffer, position, data, offset, chunk)
                    position += chunk
                    offset += chunk
                }
                return data
            }

            private fun byte(): Int {
                if (position >= limit && !fill()) throw EOFException("Unexpected end of data.")
                return buffer[position++].toInt() and 0xFF
            }
        }

        /**
         * On-disk cache for ELF symbol scan results. Keyed by a digest of the APK paths, sizes,
         * modification times and the target ABI, so an app update invalidates the cache.
         */
        private class NativeLibraryCache private constructor(private val cacheFile: File) {

            companion object {
                private const val CACHE_VERSION = 1
                private const val CACHE_MAGIC = 0x4E4C5343 // "NLSC"

                fun forApks(cacheDir: File, apks: List<File>, abiDirectory: String): NativeLibraryCache {
                    val digest = MessageDigest.getInstance("MD5")
                    digest.update(abiDirectory.toByteArray(Charsets.UTF_8))
                    for (apk in apks.sortedBy { it.absolutePath }) {
                        digest.update(apk.absolutePath.toByteArray(Charsets.UTF_8))
                        digest.update(apk.length().toString().toByteArray(Charsets.UTF_8))
                        digest.update(apk.lastModified().toString().toByteArray(Charsets.UTF_8))
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    return NativeLibraryCache(File(cacheDir, "native_libs_$hex.cache"))
                }
            }

            data class CachedLibraries(
                val libraries: Map<String, List<NativeLibrary>>,
            )

            fun load(): CachedLibraries? = runCatching {
                FileInputStream(cacheFile).use { stream ->
                    val input = LeReader(stream)
                    if (input.u32().toInt() != CACHE_MAGIC) return@runCatching null
                    if (input.u32().toInt() != CACHE_VERSION) return@runCatching null
                    val abiCount = input.u32().toInt()
                    val result = LinkedHashMap<String, List<NativeLibrary>>()
                    repeat(abiCount) {
                        val abiLength = input.u32().toInt()
                        val abi = String(input.bytes(abiLength), Charsets.UTF_8)
                        val libCount = input.u32().toInt()
                        val libs = ArrayList<NativeLibrary>(libCount)
                        repeat(libCount) {
                            val nameLength = input.u32().toInt()
                            val name = String(input.bytes(nameLength), Charsets.UTF_8)
                            val symCount = input.u32().toInt()
                            val syms = HashSet<String>(symCount)
                            repeat(symCount) {
                                val symLength = input.u32().toInt()
                                syms += String(input.bytes(symLength), Charsets.UTF_8)
                            }
                            libs += NativeLibrary(name, syms)
                        }
                        result[abi] = libs
                    }
                    CachedLibraries(result)
                }
            }.getOrNull()

            fun save(libraries: Map<String, List<NativeLibrary>>) = runCatching {
                cacheFile.parentFile?.mkdirs()
                FileOutputStream(cacheFile).use { output ->
                    val buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putInt(CACHE_MAGIC)
                    buffer.putInt(CACHE_VERSION)
                    buffer.putInt(libraries.size)
                    buffer.flip()
                    writeFully(output.channel, buffer)

                    for ((abi, libs) in libraries) {
                        val abiBytes = abi.toByteArray(Charsets.UTF_8)
                        val abiBuffer = ByteBuffer.allocate(4 + abiBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                        abiBuffer.putInt(abiBytes.size)
                        abiBuffer.put(abiBytes)
                        abiBuffer.flip()
                        writeFully(output.channel, abiBuffer)

                        val libBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        libBuffer.putInt(libs.size)
                        libBuffer.flip()
                        writeFully(output.channel, libBuffer)

                        for (lib in libs) {
                            val nameBytes = lib.fileName.toByteArray(Charsets.UTF_8)
                            val nameBuffer = ByteBuffer.allocate(4 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                            nameBuffer.putInt(nameBytes.size)
                            nameBuffer.put(nameBytes)
                            nameBuffer.flip()
                            writeFully(output.channel, nameBuffer)

                            val symBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            symBuffer.putInt(lib.entrySymbols.size)
                            symBuffer.flip()
                            writeFully(output.channel, symBuffer)

                            for (sym in lib.entrySymbols) {
                                val symBytes = sym.toByteArray(Charsets.UTF_8)
                                val symEntryBuffer = ByteBuffer.allocate(4 + symBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                                symEntryBuffer.putInt(symBytes.size)
                                symEntryBuffer.put(symBytes)
                                symEntryBuffer.flip()
                                writeFully(output.channel, symEntryBuffer)
                            }
                        }
                    }
                }
            }

            private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
            }
        }
    }
}
