package com.utar.runner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class PckPackTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `rewrites a config and keeps every other entry intact`() {
        val originalConfig = (
            "[configuration]\nentry_symbol = \"foo\"\n\n[libraries]\n" +
                "windows.x86_64 = \"res://bin/libfoo.windows.x86_64.dll\"\n"
            ).toByteArray()
        val binary = ByteArray(70_000) { (it % 251).toByte() }
        val source = temporaryFolder.newFile("game.pck")
        writePack(
            source,
            linkedMapOf(
                "res://project.binary" to byteArrayOf(1, 2, 3, 4),
                "res://addons/foo/foo.gdextension" to originalConfig,
                "res://assets/big.bin" to binary,
            ),
        )

        val pack = PckPack.open(source)
        assertEquals(2, pack.packVersion)
        assertEquals("4.1.4", pack.engineVersion)
        assertEquals(3, pack.entries.size)
        assertEquals(
            listOf("res://project.binary", "res://addons/foo/foo.gdextension", "res://assets/big.bin"),
            pack.entries.map { it.path },
        )

        val replacement = (
            "[configuration]\nentry_symbol = \"foo\"\n\n[libraries]\n" +
                "android.arm64 = \"res://utar_natives/arm64-v8a/libfoo.so\"\n"
            ).toByteArray()
        val target = temporaryFolder.newFile("patched.pck")
        val result = pack.rewriteInto(target, mapOf("res://addons/foo/foo.gdextension" to replacement))

        assertEquals(listOf("res://addons/foo/foo.gdextension"), result.replaced)
        assertTrue(result.bytesWritten > binary.size)

        val reopened = PckPack.open(target)
        assertEquals("4.1.4", reopened.engineVersion)
        assertEquals(pack.entries.map { it.path }, reopened.entries.map { it.path })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), reopened.readBytes(reopened.entries[0]))
        assertArrayEquals(replacement, reopened.readBytes(reopened.entries[1]))
        assertArrayEquals(binary, reopened.readBytes(reopened.entries[2]))
    }

    @Test
    fun `appends new files without disturbing the existing entries`() {
        val source = temporaryFolder.newFile("small.pck")
        val original = "one".toByteArray()
        writePack(source, linkedMapOf("res://a.txt" to original))
        val pack = PckPack.open(source)

        val shim = "extends RefCounted\n".toByteArray()
        val replacement = "two".toByteArray()
        val target = temporaryFolder.newFile("with-additions.pck")
        val result = pack.rewriteInto(
            target,
            mapOf("res://a.txt" to replacement),
            mapOf("res://utar_natives/json_utils.gd" to shim),
        )

        assertEquals(listOf("res://utar_natives/json_utils.gd"), result.added)
        val reopened = PckPack.open(target)
        assertEquals(
            listOf("res://a.txt", "res://utar_natives/json_utils.gd"),
            reopened.entries.map { it.path },
        )
        assertArrayEquals(replacement, reopened.readBytes(reopened.entries[0]))
        assertArrayEquals(shim, reopened.readBytes(reopened.entries[1]))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects packs the 4_1 runtime cannot load`() {
        val source = temporaryFolder.newFile("future.pck")
        writePack(source, linkedMapOf("res://a.txt" to "a".toByteArray()), formatVersion = 3)
        PckPack.open(source)
    }

    private fun writePack(file: File, files: Map<String, ByteArray>, formatVersion: Int = 2) {
        val alignment = 32L
        var cursor = 0L
        val placed = files.map { (path, data) ->
            val start = align(cursor, alignment)
            cursor = start + data.size
            Placed(path, data, start)
        }
        val indexSize = 4L + placed.sumOf { 4L + paddedPathLength(it.path) + 8 + 8 + 16 + 4 }
        val fileBase = align(96L + indexSize, alignment)

        val header = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(PckPack.MAGIC)
        header.putInt(formatVersion)
        header.putInt(4)
        header.putInt(1)
        header.putInt(4)
        header.putInt(0)
        header.putLong(fileBase)
        repeat(16) { header.putInt(0) }
        header.flip()

        val index = ByteBuffer.allocate(indexSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        index.putInt(placed.size)
        for (item in placed) {
            val pathBytes = item.path.toByteArray()
            val padded = paddedPathLength(item.path).toInt()
            index.putInt(padded)
            index.put(pathBytes)
            repeat(padded - pathBytes.size) { index.put(0) }
            index.putLong(item.offset)
            index.putLong(item.data.size.toLong())
            index.put(MessageDigest.getInstance("MD5").digest(item.data))
            index.putInt(0)
        }
        index.flip()

        val output = ByteArrayOutputStream()
        output.write(header.array())
        output.write(index.array())
        output.write(ByteArray((fileBase - output.size()).toInt()))
        for (item in placed) {
            val start = fileBase + item.offset
            output.write(ByteArray((start - output.size()).toInt()))
            output.write(item.data)
        }
        file.writeBytes(output.toByteArray())
    }

    private data class Placed(val path: String, val data: ByteArray, val offset: Long)

    private fun align(value: Long, alignment: Long): Long = (value + alignment - 1) / alignment * alignment

    private fun paddedPathLength(path: String): Long {
        val length = path.toByteArray().size.toLong()
        val remainder = length % 4
        return if (remainder == 0L) length else length + (4 - remainder)
    }
}
