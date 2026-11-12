package com.utar.runner.core

import java.io.BufferedInputStream
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

    data class RewriteResult(val replaced: List<String>, val bytesWritten: Long)

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
     * [replacements]. Every other entry is copied byte for byte, keeping its recorded MD5.
     *
     * MD5 hashes are trusted metadata for unmodified entries (Godot only verifies them for
     * encrypted payloads) but are recomputed for anything we rewrite.
     */
    fun rewriteInto(
        target: File,
        replacements: Map<String, ByteArray>,
        onProgress: ((writtenBytes: Long) -> Unit)? = null,
    ): RewriteResult {
        require(target.absolutePath != file.absolutePath) { "Refusing to overwrite the source pack." }
        val knownPaths = entries.mapTo(HashSet()) { it.path }
        val unknown = replacements.keys - knownPaths
        require(unknown.isEmpty()) { "Pack has no entries named ${unknown.joinToString()}" }

        data class Planned(
            val entry: Entry,
            val payload: ByteArray?,
            val size: Long,
            val md5: ByteArray,
            val relativeOffset: Long,
        )

        var cursor = 0L
        val planned = ArrayList<Planned>(entries.size)
        for (entry in entries) {
            val payload = replacements[entry.path]
            val size = payload?.size?.toLong() ?: entry.size
            val md5 = payload?.let { md5(it) } ?: entry.md5
            val start = align(cursor)
            planned += Planned(entry, payload, size, md5, start)
            cursor = start + size
        }

        val indexSize = 4L + planned.sumOf { item ->
            4L + paddedPathLength(item.entry.path) + 8L + 8L + 16L + 4L
        }
        val fileBase = align(HEADER_SIZE + indexSize)

        val index = ByteBuffer.allocate(indexSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        index.putInt(planned.size)
        for (item in planned) {
            val pathBytes = item.entry.path.toByteArray(Charsets.UTF_8)
            val padded = paddedPathLength(item.entry.path)
            index.putInt(padded.toInt())
            index.put(pathBytes)
            repeat((padded - pathBytes.size).toInt()) { index.put(0) }
            index.putLong(item.relativeOffset)
            index.putLong(item.size)
            index.put(item.md5)
            index.putInt(item.entry.flags)
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
                        var position = item.entry.offset
                        while (remaining > 0L) {
                            val copied = source.channel.transferTo(position, remaining, channel)
                            if (copied <= 0L) throw IOException("Unexpected end of pack while copying ${item.entry.path}")
                            position += copied
                            remaining -= copied
                            written += copied
                        }
                    }
                    onProgress?.invoke(written)
                }
            }
        }

        return RewriteResult(replacements.keys.toList(), target.length())
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
            FileInputStream(file).use { stream ->
                val input = LeReader(BufferedInputStream(stream, 64 * 1024))
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

                return PckPack(file, version, major, minor, patch, entries)
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

    /** Small little-endian reader used for the header and index. */
    private class LeReader(private val input: InputStream) {

        fun u32(): Long {
            var value = 0L
            repeat(4) { shift ->
                value = value or (byte().toLong() shl (shift * 8))
            }
            return value
        }

        fun u64(): Long {
            var value = 0L
            repeat(8) { shift ->
                value = value or (byte().toLong() shl (shift * 8))
            }
            return value
        }

        fun bytes(length: Int): ByteArray {
            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(data, offset, length - offset)
                if (read < 0) throw EOFException("Unexpected end of pack index.")
                offset += read
            }
            return data
        }

        private fun byte(): Int {
            val value = input.read()
            if (value < 0) throw EOFException("Unexpected end of pack header.")
            return value
        }
    }
}
