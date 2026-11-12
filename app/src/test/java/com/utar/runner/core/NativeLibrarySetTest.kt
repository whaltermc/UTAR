package com.utar.runner.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeLibrarySetTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `finds extension libraries packaged inside the apk`() {
        val apk = temporaryFolder.newFile("app.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            fun add(name: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
            add("lib/arm64-v8a/libthreen.so")
            add("lib/arm64-v8a/libgodot_android.so")
            add("lib/arm64-v8a/libfmod.so")
            add("lib/arm64-v8a/libfmodstudio.so")
            add("lib/x86_64/libthreen.so")
            add("assets/json_utils.gd")
        }

        val natives = NativeLibrarySet.fromApks(listOf(apk), "arm64-v8a")

        assertEquals(listOf("libthreen.so"), natives.allLibraries)
    }
}
