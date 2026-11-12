package com.utar.runner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.IntentCompat
import com.utar.runner.runtime.Backend
import com.utar.runner.runtime.SessionLog
import com.utar.runner.ui.LoadingOverlay
import com.utar.runner.ui.TouchControlsOverlay
import com.utar.runner.ui.UtarTheme
import org.godotengine.godot.FullScreenGodotApp
import java.io.File

/**
 * Hosts the Godot 4.1.4 runtime. [org.godotengine.godot.Godot] merges the list returned here
 * into the engine command line, which is how the installed pack and the renderer are selected.
 *
 * Godot 4.1 runs `Main::setup` (pack indexing) on this activity's UI thread inside
 * `super.onCreate`, so nothing in this process can draw until it returns. That is why the
 * launcher process shows the "starting" screen for that phase, and why this activity only builds
 * its own Material 3 overlay *after* `super.onCreate`:
 *   • loading screen from first paint until Godot's main loop starts
 *   • touch controls (WASD / Space / 1-2-3 / arrow keys) afterwards
 */
class GameActivity : FullScreenGodotApp() {

    private val readyReceiver: ResultReceiver? by lazy {
        IntentCompat.getParcelableExtra(
            intent,
            EXTRA_READY_RECEIVER,
            ResultReceiver::class.java,
        )
    }

    @Volatile private var readySignaled = false

    private var showLoading by mutableStateOf(true)
    private var loadingMessage by mutableStateOf("Preparing the engine…")
    private var touchControlsEnabled by mutableStateOf(true)
    private var arrowKeysVisible by mutableStateOf(false)
    private var overlayOpacity by mutableStateOf(0.42f)

    override fun onCreate(savedInstanceState: Bundle?) {
        SessionLog.mark("GameActivity.onCreate: Godot setup begins (UI thread blocks until the pack is indexed)")
        super.onCreate(savedInstanceState)
        SessionLog.mark("GameActivity.onCreate: Godot setup returned")
        SessionLog.recordProcessId()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ImmersiveMode.apply(this)

        installOverlay()
        signalLauncherOnFirstFrame()
    }

    /**
     * The overlay is added to the content frame (not the decor) once Godot's views exist. Window
     * flags are deliberately never toggled while the engine starts: changing FLAG_NOT_FOCUSABLE /
     * FLAG_NOT_TOUCHABLE forces a window relayout, which can recreate the Vulkan/GL surface right
     * as the render thread is initialising. The loading screen blocks touches by itself instead.
     */
    private fun installOverlay() {
        val view = ComposeView(this).apply {
            setContent {
                UtarTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TouchControlsOverlay(
                            touchControlsEnabled = touchControlsEnabled,
                            hardwareInputConnected = false,
                            arrowKeysVisible = arrowKeysVisible,
                            overlayOpacity = overlayOpacity,
                            onToggleTouchControls = { touchControlsEnabled = it },
                            onToggleArrowKeys = { arrowKeysVisible = it },
                            onVirtualKey = ::dispatchVirtualKey,
                            visible = !showLoading,
                        )
                        LoadingOverlay(show = showLoading, message = loadingMessage)
                    }
                }
            }
        }
        addContentView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * Tells the launcher process it can drop its "starting" screen once this window has painted.
     *
     * A draw listener must never remove itself from inside onDraw(): for apps targeting API 26+
     * ViewTreeObserver throws IllegalStateException ("Cannot call removeOnDrawListener inside of
     * onDraw"), which crashed the game the moment its window first appeared. Removal is posted.
     */
    private fun signalLauncherOnFirstFrame() {
        val decor = window.decorView
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var fired = false

            override fun onDraw() {
                if (fired) return
                fired = true
                SessionLog.mark("GameActivity first frame drawn")
                signalLauncherReady()
                val self = this
                decor.post {
                    val observer = decor.viewTreeObserver
                    if (observer.isAlive) observer.removeOnDrawListener(self)
                }
            }
        }
        decor.viewTreeObserver.addOnDrawListener(listener)
    }

    private fun signalLauncherReady() {
        if (readySignaled) return
        readySignaled = true
        runCatching { readyReceiver?.send(Activity.RESULT_OK, null) }
            .onFailure { SessionLog.w("Couldn't notify the launcher: ${it.message}") }
    }

    private fun dispatchVirtualKey(keyCode: Int, pressed: Boolean) {
        val now = System.currentTimeMillis()
        val event = KeyEvent(
            now,
            now,
            if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )
        val handler = getGodotFragment()?.getRenderView()?.inputHandler ?: return
        if (pressed) handler.onKeyDown(keyCode, event) else handler.onKeyUp(keyCode, event)
    }

    private fun setLoading(show: Boolean, message: String) {
        val update = Runnable {
            showLoading = show
            if (message.isNotEmpty()) loadingMessage = message
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            update.run()
        } else {
            window.decorView.post(update)
        }
    }

    override fun onResume() {
        super.onResume()
        ImmersiveMode.apply(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ImmersiveMode.apply(this)
    }

    override fun onGodotSetupCompleted() {
        super.onGodotSetupCompleted()
        SessionLog.mark("Godot setup completed")
        setLoading(true, "Loading game data…")
    }

    /** Called on Godot's render thread, so it must only touch the UI through [setLoading]. */
    override fun onGodotMainLoopStarted() {
        super.onGodotMainLoopStarted()
        SessionLog.mark("Godot main loop started")
        signalLauncherReady()
        setLoading(false, "")
    }

    override fun onDestroy() {
        SessionLog.mark("GameActivity destroyed")
        super.onDestroy()
    }

    override fun getCommandLine(): List<String> {
        val args = this.intent.getStringArrayExtra(EXTRA_COMMAND_LINE)?.toList().orEmpty()
        SessionLog.i("Engine command line: ${args.joinToString(" ")}")
        return args
    }

    companion object {
        private const val EXTRA_COMMAND_LINE = "com.utar.runner.extra.COMMAND_LINE"
        private const val EXTRA_READY_RECEIVER = "com.utar.runner.extra.READY_RECEIVER"

        fun engineCommandLine(pack: File, backend: Backend): List<String> = buildList {
            add("--main-pack")
            add(pack.absolutePath)
            addAll(backend.engineArgs)
        }

        fun createIntent(
            context: Context,
            pack: File,
            backend: Backend,
            readyReceiver: ResultReceiver? = null,
        ): Intent =
            Intent(context, GameActivity::class.java).apply {
                putExtra(EXTRA_COMMAND_LINE, engineCommandLine(pack, backend).toTypedArray())
                if (readyReceiver != null) putExtra(EXTRA_READY_RECEIVER, readyReceiver)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
