package com.utar.runner.core

import org.junit.Test
import java.io.File

class GamePatchTest {
    @Test
    fun gamePatchApplies() {
        val source = File("/storage/emulated/0/Download/Until Then/UntilThen.pck")
        if (!source.isFile) return
        val pack = PckPack.open(source)
        val entry = pack.entries.firstOrNull { it.path == "res://Game.gd" } ?: return
        val original = String(pack.readBytes(entry))
        val old = """elif save.md5 != save.compute_md5():
		#something's not right
		#consider savegame corrupted
		printerr("ERROR: Save corruption detected.")
		corrupted_flag = true
		return false"""
        val patched = original.replace(old, "PLACEHOLDER")
        println("md5patch_applies=${patched != original}")
    }
}
