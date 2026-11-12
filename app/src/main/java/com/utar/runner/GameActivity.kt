package com.utar.runner

import android.content.Context
import android.content.Intent
import com.utar.runner.runtime.Backend
import com.utar.runner.runtime.SessionLog
import org.godotengine.godot.FullScreenGodotApp
import java.io.File

/**
 * Hosts the Godot 4.1.4 runtime. [org.godotengine.godot.Godot] merges the list returned here
 * into the engine command line, which is how the installed pack and the renderer are selected.
 */
class GameActivity : FullScreenGodotApp() {

    override fun getCommandLine(): List<String> {
        val args = this.intent.getStringArrayExtra(EXTRA_COMMAND_LINE)?.toList().orEmpty()
        SessionLog.i("Engine command line: ${args.joinToString(" ")}")
        return args
    }

    companion object {
        private const val EXTRA_COMMAND_LINE = "com.utar.runner.extra.COMMAND_LINE"

        fun engineCommandLine(pack: File, backend: Backend): List<String> = buildList {
            add("--main-pack")
            add(pack.absolutePath)
            addAll(backend.engineArgs)
        }

        fun createIntent(context: Context, pack: File, backend: Backend): Intent =
            Intent(context, GameActivity::class.java).apply {
                putExtra(EXTRA_COMMAND_LINE, engineCommandLine(pack, backend).toTypedArray())
            }
    }
}
