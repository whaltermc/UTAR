package com.utar.runner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GdextensionRewriterTest {

    private val desktopConfig = """
        [configuration]
        entry_symbol = "foo_library_init"
        compatibility_minimum = "4.1"

        [libraries]
        windows.x86_64 = "res://bin/libfoo.windows.x86_64.dll"
        linux.x86_64 = "res://bin/libfoo.linux.x86_64.so"
    """.trimIndent()

    @Test
    fun `adds the android library and keeps the desktop entries`() {
        val natives = setOf("arm64-v8a" to NativeLibrarySet.NativeLibrary("libfoo.android.arm64.so"))

        val plan = GdextensionRewriter.plan("res://foo.gdextension", desktopConfig, natives)

        assertTrue(plan.patched)
        assertEquals(mapOf("arm64-v8a" to "libfoo.android.arm64.so"), plan.libraries)
        assertTrue(plan.text.contains("windows.x86_64 = \"res://bin/libfoo.windows.x86_64.dll\""))
        assertTrue(plan.text.contains("android.arm64 = \"res://utar_natives/arm64-v8a/libfoo.android.arm64.so\""))
    }

    @Test
    fun `replaces an existing android entry instead of duplicating it`() {
        val natives = setOf("arm64-v8a" to NativeLibrarySet.NativeLibrary("libfoo.android.arm64.so"))
        val withStaleEntry = desktopConfig + "\nandroid.arm64 = \"res://bin/stale.so\"\n"

        val plan = GdextensionRewriter.plan("res://foo.gdextension", withStaleEntry, natives)

        assertEquals(1, plan.text.lines().count { it.startsWith("android.arm64") })
        assertFalse(plan.text.contains("stale.so"))
    }

    @Test
    fun `matches by entry symbol when the built library name differs`() {
        val natives = NativeLibrarySet(
            mapOf(
                "arm64-v8a" to listOf(
                    NativeLibrarySet.NativeLibrary("libfmod_extension.so", setOf("fmod_library_init")),
                    NativeLibrarySet.NativeLibrary("libinkcpp.so", setOf("inkcpp_library_init", "utink_library_init")),
                ),
            ),
        )
        val config = """
            [configuration]
            entry_symbol = "inkcpp_library_init"

            [libraries]
            windows.x86_64 = "res://addons/inkcpp/bin/inkcpp.windows.x86_64.dll"
        """.trimIndent()

        val plan = GdextensionRewriter.plan("res://addons/inkcpp/inkcpp.gdextension", config, natives)

        assertEquals(mapOf("arm64-v8a" to "libinkcpp.so"), plan.libraries)
        assertTrue(plan.text.contains("android.arm64 = \"res://utar_natives/arm64-v8a/libinkcpp.so\""))
    }

    @Test
    fun `leaves the config alone when no android build exists for it`() {
        val natives = NativeLibrarySet(
            mapOf(
                "arm64-v8a" to listOf(
                    NativeLibrarySet.NativeLibrary("libunrelated.android.arm64.so", setOf("other_library_init")),
                    NativeLibrarySet.NativeLibrary("libsecond.android.arm64.so", setOf("second_library_init")),
                ),
            ),
        )

        val plan = GdextensionRewriter.plan("res://foo.gdextension", desktopConfig, natives)

        assertFalse(plan.patched)
        assertEquals(desktopConfig, plan.text)
    }

    private fun setOf(vararg libraries: Pair<String, NativeLibrarySet.NativeLibrary>): NativeLibrarySet =
        NativeLibrarySet(libraries.groupBy({ it.first }, { it.second }))
}
