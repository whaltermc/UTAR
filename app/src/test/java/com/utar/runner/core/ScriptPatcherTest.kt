package com.utar.runner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptPatcherTest {

    @Test
    fun `trims 5 args to 4`() {
        val source = "var r = ResourceLoader.load_threaded_request(\"res://a.tscn\", null, true, 0, extra_arg)"
        val (patched, changes) = ScriptPatcher.trimLoadThreadedRequestArgs(source)

        assertEquals(1, changes.size)
        assertEquals(
            "load_threaded_request(\"res://a.tscn\", null, true, 0, extra_arg)",
            changes[0].first,
        )
        assertEquals(
            "load_threaded_request(\"res://a.tscn\", null, true, 0)",
            changes[0].second,
        )
        assertEquals(
            "var r = load_threaded_request(\"res://a.tscn\", null, true, 0)",
            patched,
        )
    }

    @Test
    fun `leaves 4 arg call unchanged`() {
        val source = "ResourceLoader.load_threaded_request(\"res://a.tscn\", null, true, 0)"
        val (patched, changes) = ScriptPatcher.trimLoadThreadedRequestArgs(source)

        assertEquals(0, changes.size)
        assertEquals(source, patched)
    }

    @Test
    fun `leaves 3 arg call unchanged`() {
        val source = "ResourceLoader.load_threaded_request(\"res://a.tscn\", null, true)"
        val (patched, changes) = ScriptPatcher.trimLoadThreadedRequestArgs(source)

        assertEquals(0, changes.size)
        assertEquals(source, patched)
    }

    @Test
    fun `respects nested call as argument`() {
        val source = "ResourceLoader.load_threaded_request(\"a.tscn\", preload(\"b.gd\").new(), true, 0, extra)"
        val (_, changes) = ScriptPatcher.trimLoadThreadedRequestArgs(source)

        assertEquals(1, changes.size)
        // Nested preload call contains a comma inside parens; must not split args.
        assertEquals(
            "load_threaded_request(\"a.tscn\", preload(\"b.gd\").new(), true, 0, extra)",
            changes[0].first,
        )
        assertEquals(
            "load_threaded_request(\"a.tscn\", preload(\"b.gd\").new(), true, 0)",
            changes[0].second,
        )
    }

    @Test
    fun `respects string containing comma and paren`() {
        val source = "ResourceLoader.load_threaded_request(\"res://a, (b).tscn\", null, true, 0, extra)"
        val (_, changes) = ScriptPatcher.trimLoadThreadedRequestArgs(source)

        assertEquals(1, changes.size)
        assertEquals(
            "load_threaded_request(\"res://a, (b).tscn\", null, true, 0, extra)",
            changes[0].first,
        )
        assertEquals(
            "load_threaded_request(\"res://a, (b).tscn\", null, true, 0)",
            changes[0].second,
        )
    }

    @Test
    fun `multiple calls some trimmed some not`() {
        val source = """
            var a = load_threaded_request("x.tscn", null, true, 0, drop1)
            var b = load_threaded_request("y.tscn", null, false, 1)
            var c = load_threaded_request("z.tscn", null, true, 0, drop2, drop3)
        """.trimIndent()
        val (patched, changes) = ScriptPatcher.trimLoadThreadedRequestArgs(source)

        assertEquals(2, changes.size)
        // First call: 5 args -> trim to 4
        assertEquals(
            "load_threaded_request(\"x.tscn\", null, true, 0, drop1)",
            changes[0].first,
        )
        assertEquals(
            "load_threaded_request(\"x.tscn\", null, true, 0)",
            changes[0].second,
        )
        // Third call: 6 args -> trim to 4
        assertEquals(
            "load_threaded_request(\"z.tscn\", null, true, 0, drop2, drop3)",
            changes[1].first,
        )
        assertEquals(
            "load_threaded_request(\"z.tscn\", null, true, 0)",
            changes[1].second,
        )
        // Middle call (4 args) unchanged - verify it's still in patched
        assertTrue(patched.contains("load_threaded_request(\"y.tscn\", null, false, 1)"))
    }

    @Test
    fun `no calls returns source unchanged`() {
        val source = "var x = 1 + 2"
        val (patched, changes) = ScriptPatcher.trimLoadThreadedRequestArgs(source)

        assertEquals(0, changes.size)
        assertEquals(source, patched)
    }

    @Test
    fun `case insensitive match`() {
        val source = "ResourceLoader.LOAD_THREADED_REQUEST(\"a.tscn\", null, true, 0, extra)"
        val (_, changes) = ScriptPatcher.trimLoadThreadedRequestArgs(source)

        assertEquals(1, changes.size)
        assertEquals(
            "load_threaded_request(\"a.tscn\", null, true, 0, extra)",
            changes[0].second,
        )
    }

    @Test
    fun `string with escaped quote`() {
        val source = "load_threaded_request(\"res://a\\\"b.tscn\", null, true, 0, extra)"
        val (_, changes) = ScriptPatcher.trimLoadThreadedRequestArgs(source)

        assertEquals(1, changes.size)
        assertEquals(
            "load_threaded_request(\"res://a\\\"b.tscn\", null, true, 0, extra)",
            changes[0].first,
        )
        assertEquals(
            "load_threaded_request(\"res://a\\\"b.tscn\", null, true, 0)",
            changes[0].second,
        )
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
