package com.utar.runner.core

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Reader/writer for Godot 4.1 `.pck` archives (pack format version 2).
 *
 * On-disk layout:
 * ```
 * header  : magic "GDPC" | version | major | minor | patch | flags | file_base | 16 * u32 reserved
 * index   : entry count, then per entry: padded path length | path | offset | size | md5 | flags
 * padding : zero bytes up to file_base
 * data    : file payloads, each aligned, offsets stored relative to file_base
 * ```
 * [rewriteInto] streams every payload, so a multi-gigabyte pack only needs one write pass
 * and no full in-memory copy.
 */
class PckPack private constructor(
    val file: File,
    val packVersion: Int,
    val engineMajor: Int,
    val engineMinor: Int,
    val enginePatch: Int,
    val entries: List<Entry>,
) {

    val engineVersion: String get() = "$engineMajor.$engineMinor.$enginePatch"

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
        val data = ByteArray(entry.size.toInt())
        RandomAccessFile(file, "r").use { source ->
            source.seek(entry.offset)
            source.readFully(data)
        }
        return data
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

        data class Planned(
            val path: String,
            val payload: ByteArray?,
            val sourceOffset: Long,
            val size: Long,
            val md5: ByteArray,
            val flags: Int,
            val relativeOffset: Long,
        )

        var cursor = 0L
        val planned = ArrayList<Planned>(entries.size + newFiles.size)

        fun plan(path: String, payload: ByteArray?, sourceOffset: Long, sourceSize: Long, sourceMd5: ByteArray, flags: Int) {
            val size = payload?.size?.toLong() ?: sourceSize
            val hash = payload?.let { md5(it) } ?: sourceMd5
            val start = align(cursor)
            planned += Planned(path, payload, sourceOffset, size, hash, flags, start)
            cursor = start + size
        }

        for (entry in entries) {
            plan(entry.path, effectiveReplacements[entry.path], entry.offset, entry.size, entry.md5, entry.flags)
        }
        for ((path, payload) in newFiles) {
            plan(path, payload, 0L, payload.size.toLong(), md5(payload), 0)
        }

        val indexSize = 4L + planned.sumOf { item ->
            4L + paddedPathLength(item.path) + 8L + 8L + 16L + 4L
        }
        val fileBase = align(HEADER_SIZE + indexSize)

        val index = ByteBuffer.allocate(indexSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        index.putInt(planned.size)
        for (item in planned) {
            val pathBytes = item.path.toByteArray(Charsets.UTF_8)
            val padded = paddedPathLength(item.path)
            index.putInt(padded.toInt())
            index.put(pathBytes)
            repeat((padded - pathBytes.size).toInt()) { index.put(0) }
            index.putLong(item.relativeOffset)
            index.putLong(item.size)
            index.put(item.md5)
            index.putInt(item.flags)
        }
        check(index.position() == index.capacity()) { "Pack index size mismatch." }
        index.flip()

        val header = ByteBuffer.allocate(HEADER_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(MAGIC)
        header.putInt(SUPPORTED_PACK_VERSION)
        header.putInt(engineMajor)
        header.putInt(engineMinor)
        header.putInt(enginePatch)
        header.putInt(0) // pack flags: unencrypted directory
        header.putLong(fileBase)
        repeat(16) { header.putInt(0) }
        header.flip()

        target.parentFile?.mkdirs()
        RandomAccessFile(file, "r").use { source ->
            FileOutputStream(target).use { output ->
                val channel = output.channel
                writeFully(channel, header)
                writeFully(channel, index)

                var written = HEADER_SIZE + indexSize
                if (fileBase > written) {
                    writeZeros(channel, fileBase - written)
                    written = fileBase
                }

                for (item in planned) {
                    val start = fileBase + item.relativeOffset
                    if (start > written) {
                        writeZeros(channel, start - written)
                        written = start
                    }
                    val payload = item.payload
                    if (payload != null) {
                        writeFully(channel, ByteBuffer.wrap(payload))
                        written += payload.size
                    } else {
                        var remaining = item.size
                        var position = item.sourceOffset
                        while (remaining > 0L) {
                            val copied = source.channel.transferTo(position, remaining, channel)
                            if (copied <= 0L) throw IOException("Unexpected end of pack while copying ${item.path}")
                            position += copied
                            remaining -= copied
                            written += copied
                        }
                    }
                    onProgress?.invoke(written)
                }
            }
        }

        return RewriteResult(effectiveReplacements.keys.toList(), newFiles.keys.toList(), target.length())
    }

    companion object {
        const val MAGIC = 0x43504447 // "GDPC"
        const val SUPPORTED_PACK_VERSION = 2
        const val PACK_FILE_ENCRYPTED = 1
        const val PACK_DIR_ENCRYPTED = 1

        private const val HEADER_SIZE = 96L
        private const val ALIGNMENT = 32L
        private const val DEFAULT_READ_LIMIT = 4L * 1024 * 1024
        private const val MAX_ENTRIES = 1_000_000
        private const val MAX_PATH_LENGTH = 8192

        fun open(file: File): PckPack {
            require(file.isFile) { "Pack not found: ${file.name}" }
            val cache = PckPackCache.forFile(file)
            val cached = cache.load()
            if (cached != null && cached.fileSize == file.length() && cached.lastModified == file.lastModified()) {
                val entries = cached.entries.map { e ->
                    Entry(e.path, e.offset, e.size, e.md5, e.flags)
                }
                return PckPack(file, cached.version, cached.major, cached.minor, cached.patch, entries)
            }

            FileInputStream(file).use { stream ->
                val input = LeReader(stream)
                require(input.u32() == MAGIC.toLong()) { "Not a Godot pack: missing GDPC header." }

                val version = input.u32().toInt()
                require(version == SUPPORTED_PACK_VERSION) {
                    "Pack format v$version is not supported by the Godot 4.1 runtime (it loads format v2 packs)."
                }

                val major = input.u32().toInt()
                val minor = input.u32().toInt()
                val patch = input.u32().toInt()
                val flags = input.u32().toInt()
                val fileBase = input.u64()
                repeat(16) { input.u32() }

                require((flags and PACK_DIR_ENCRYPTED) == 0) {
                    "Encrypted pack directories are not supported. Export the game without pack encryption."
                }

                val count = input.u32().toInt()
                require(count in 0..MAX_ENTRIES) { "Corrupt pack index: implausible entry count ($count)." }

                val entries = ArrayList<Entry>(count)
                repeat(count) {
                    val pathLength = input.u32().toInt()
                    require(pathLength in 1..MAX_PATH_LENGTH) { "Corrupt pack index: bad path length ($pathLength)." }
                    val path = String(input.bytes(pathLength), Charsets.UTF_8).trimEnd('\u0000')
                    val relativeOffset = input.u64()
                    val size = input.u64()
                    val md5 = input.bytes(16)
                    val entryFlags = input.u32().toInt()
                    require(fileBase + relativeOffset + size <= file.length()) {
                        "Corrupt pack index: $path points outside the pack."
                    }
                    entries += Entry(path, fileBase + relativeOffset, size, md5, entryFlags)
                }

                val pack = PckPack(file, version, major, minor, patch, entries)
                cache.save(pack)
                return pack
            }
        }

        /**
         * Sidecar cache for PCK index metadata. Stores entry offsets, sizes and MD5s next to the
         * pack file so re-opening a multi-thousand-resource pack does not re-parse its index.
         *
         * The cache is invalidated whenever the pack's size or last-modified time changes, which
         * covers re-installation and patching. A corrupt or unreadable cache simply falls back to
         * parsing the index and rewrites the cache.
         */
        private class PckPackCache private constructor(private val metaFile: File) {

            companion object {
                private const val CACHE_VERSION = 1
                private const val CACHE_MAGIC = 0x50434B4D // "PCKM"
                private val lock = Any()

                fun forFile(pack: File): PckPackCache =
                    PckPackCache(File(pack.absolutePath + ".meta"))
            }

            data class EntryData(
                val path: String,
                val offset: Long,
                val size: Long,
                val md5: ByteArray,
                val flags: Int,
            )

            data class CacheData(
                val fileSize: Long,
                val lastModified: Long,
                val version: Int,
                val major: Int,
                val minor: Int,
                val patch: Int,
                val flags: Int,
                val entries: List<EntryData>,
            )

            fun load(): CacheData? = synchronized(lock) {
                if (!metaFile.isFile) return@synchronized null
                runCatching {
                    FileInputStream(metaFile).use { stream ->
                        val input = LeReader(stream)
                        if (input.u32().toInt() != CACHE_MAGIC) return@runCatching null
                        if (input.u32().toInt() != CACHE_VERSION) return@runCatching null
                        val fileSize = input.u64()
                        val lastModified = input.u64()
                        val version = input.u32().toInt()
                        val major = input.u32().toInt()
                        val minor = input.u32().toInt()
                        val patch = input.u32().toInt()
                        val flags = input.u32().toInt()
                        repeat(16) { input.u32() }
                        val count = input.u32().toInt()
                        if (count < 0 || count > MAX_ENTRIES) return@runCatching null
                        val entries = ArrayList<EntryData>(count)
                        repeat(count) {
                            val pathLength = input.u32().toInt()
                            if (pathLength < 1 || pathLength > MAX_PATH_LENGTH) return@runCatching null
                            val path = String(input.bytes(pathLength), Charsets.UTF_8).trimEnd('\u0000')
                            val offset = input.u64()
                            val size = input.u64()
                            val md5 = input.bytes(16)
                            val entryFlags = input.u32().toInt()
                            entries += EntryData(path, offset, size, md5, entryFlags)
                        }
                        CacheData(fileSize, lastModified, version, major, minor, patch, flags, entries)
                    }
                }.getOrNull()
            }

            fun save(pack: PckPack) = synchronized(lock) {
                runCatching {
                    FileOutputStream(metaFile).use { output ->
                        val buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.putInt(CACHE_MAGIC)
                        buffer.putInt(CACHE_VERSION)
                        buffer.putLong(pack.file.length())
                        buffer.putLong(pack.file.lastModified())
                        buffer.putInt(pack.packVersion)
                        buffer.putInt(pack.engineMajor)
                        buffer.putInt(pack.engineMinor)
                        buffer.putInt(pack.enginePatch)
                        buffer.putInt(0) // flags
                        buffer.putLong(0) // fileBase placeholder
                        repeat(16) { buffer.putInt(0) }
                        buffer.putInt(pack.entries.size)
                        buffer.flip()
                        writeFullyToChannel(output.channel, buffer)

                        for (entry in pack.entries) {
                            val pathBytes = entry.path.toByteArray(Charsets.UTF_8)
                            val padded = paddedPathLength(entry.path)
                            val entryBuffer = ByteBuffer.allocate(4 + padded.toInt() + 8 + 8 + 16 + 4).order(ByteOrder.LITTLE_ENDIAN)
                            entryBuffer.putInt(padded.toInt())
                            entryBuffer.put(pathBytes)
                            repeat((padded - pathBytes.size).toInt()) { entryBuffer.put(0) }
                            entryBuffer.putLong(entry.offset)
                            entryBuffer.putLong(entry.size)
                            entryBuffer.put(entry.md5)
                            entryBuffer.putInt(entry.flags)
                            entryBuffer.flip()
                            writeFullyToChannel(output.channel, entryBuffer)
                        }
                    }
                }
            }

            private fun writeFullyToChannel(channel: FileChannel, buffer: ByteBuffer) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
            }
        }

        private fun align(value: Long): Long = (value + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT

        private fun paddedPathLength(path: String): Long {
            val length = path.toByteArray(Charsets.UTF_8).size.toLong()
            val remainder = length % 4
            return if (remainder == 0L) length else length + (4 - remainder)
        }

        private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

        private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }

        private fun writeZeros(channel: FileChannel, count: Long) {
            val zeros = ByteBuffer.allocate(if (count < 4096) count.toInt() else 4096)
            var remaining = count
            while (remaining > 0) {
                val chunk = minOf(remaining, zeros.capacity().toLong()).toInt()
                zeros.clear().limit(chunk)
                writeFully(channel, zeros)
                remaining -= chunk
            }
        }
    }

    /**
     * Small little-endian reader used for the header and index. It owns a large buffer and decodes
     * straight out of it: the previous version pulled the index one byte at a time through a
     * synchronized BufferedInputStream, which is millions of locked calls on a big pack.
     */
    private class LeReader(private val input: InputStream) {

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
                if (position >= limit && !fill()) throw EOFException("Unexpected end of pack index.")
                val chunk = minOf(length - offset, limit - position)
                System.arraycopy(buffer, position, data, offset, chunk)
                position += chunk
                offset += chunk
            }
            return data
        }

        private fun byte(): Int {
            if (position >= limit && !fill()) throw EOFException("Unexpected end of pack header.")
            return buffer[position++].toInt() and 0xFF
        }
    }
}
