package com.utar.runner.ui

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KeySize = 56.dp
private val KeyGap = 8.dp
private val KeyCornerIdle = 18.dp
private val KeyCornerPressed = 28.dp // half of KeySize: a fully round key
private val EdgeMargin = 12.dp

/**
 * Full-screen loading state shown from the game window's first frame until Godot's main loop
 * starts. It swallows touches so nothing reaches the engine while it is still booting.
 */
@Composable
fun LoadingOverlay(
    show: Boolean,
    message: String,
) {
    AnimatedVisibility(
        visible = show,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(320)) + scaleOut(targetScale = 1.04f, animationSpec = tween(320)),
    ) {
        val blockTouches = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .clickable(interactionSource = blockTouches, indication = null, onClick = {}),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ExpressiveLoader(size = 112.dp)
                Spacer(modifier = Modifier.size(2.dp))
                Text(
                    text = "Until Then",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(220.dp)
                        .clip(CircleShape),
                )
            }
        }
    }
}

/**
 * One on-screen key. The container springs from a rounded square to a full circle while it is
 * held — the Material 3 Expressive "shape morph on press" — and switches to the primary color.
 */
@Composable
private fun KeyFace(
    pressed: Boolean,
    active: Boolean,
    opacity: Float,
    modifier: Modifier = Modifier,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val highlighted = pressed || active

    val corner: Dp by animateDpAsState(
        targetValue = if (pressed) KeyCornerPressed else KeyCornerIdle,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "keyCorner",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "keyScale",
    )
    val container by animateColorAsState(
        targetValue = if (highlighted) scheme.primary else Color.Black.copy(alpha = opacity),
        animationSpec = tween(90),
        label = "keyContainer",
    )
    val outline by animateColorAsState(
        targetValue = if (highlighted) scheme.primary else Color.White.copy(alpha = 0.30f),
        animationSpec = tween(90),
        label = "keyOutline",
    )
    val shape = RoundedCornerShape(corner)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(container)
            .border(1.dp, outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        content(if (highlighted) scheme.onPrimary else Color.White)
    }
}

@Composable
private fun HeldKey(
    keyCode: Int,
    onVirtualKey: (Int, Boolean) -> Unit,
    opacity: Float,
    description: String,
    view: View,
    modifier: Modifier = Modifier,
    width: Dp = KeySize,
    content: @Composable (Color) -> Unit,
) {
    var pressed by remember(keyCode) { mutableStateOf(false) }
    KeyFace(
        pressed = pressed,
        active = false,
        opacity = opacity,
        modifier = modifier
            .size(width = width, height = KeySize)
            .pointerInput(keyCode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onVirtualKey(keyCode, true)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        onVirtualKey(keyCode, false)
                        pressed = false
                    }
                }
            }
            .semantics { contentDescription = description },
        content = content,
    )
}

@Composable
private fun HeldTextKey(
    label: String,
    keyCode: Int,
    onVirtualKey: (Int, Boolean) -> Unit,
    opacity: Float,
    view: View,
    width: Dp = KeySize,
) {
    HeldKey(
        keyCode = keyCode,
        onVirtualKey = onVirtualKey,
        opacity = opacity,
        description = label,
        view = view,
        width = width,
    ) { color ->
        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )
    }
}

@Composable
private fun HeldIconKey(
    icon: ImageVector,
    description: String,
    keyCode: Int,
    onVirtualKey: (Int, Boolean) -> Unit,
    opacity: Float,
    view: View,
) {
    HeldKey(
        keyCode = keyCode,
        onVirtualKey = onVirtualKey,
        opacity = opacity,
        description = description,
        view = view,
    ) { color ->
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
    }
}

/** Small round toggle in the top-left corner (controls on/off, arrow keys on/off). */
@Composable
private fun ToggleKey(
    onClick: () -> Unit,
    description: String,
    opacity: Float,
    active: Boolean,
    view: View,
    content: @Composable (Color) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    KeyFace(
        pressed = pressed,
        active = active,
        opacity = opacity,
        modifier = Modifier
            .size(44.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    val up = try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                    }
                    if (up != null) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        currentOnClick()
                    }
                }
            }
            .semantics {
                this.contentDescription = description
                this.role = Role.Button
            },
        content = content,
    )
}

@Composable
fun TouchControlsOverlay(
    touchControlsEnabled: Boolean,
    hardwareInputConnected: Boolean,
    arrowKeysVisible: Boolean,
    overlayOpacity: Float,
    onToggleTouchControls: (Boolean) -> Unit,
    onToggleArrowKeys: (Boolean) -> Unit,
    onVirtualKey: (Int, Boolean) -> Unit,
    visible: Boolean = true,
) {
    val view = LocalView.current
    val effective = touchControlsEnabled && !hardwareInputConnected
    val enter = fadeIn(tween(160)) + scaleIn(
        initialScale = 0.8f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
    )
    val exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.85f, animationSpec = tween(120))

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(100))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(EdgeMargin),
        ) {
            // Top-left toggles.
            Column(
                modifier = Modifier.align(Alignment.TopStart),
                verticalArrangement = Arrangement.spacedBy(KeyGap),
            ) {
                ToggleKey(
                    onClick = { onToggleTouchControls(!touchControlsEnabled) },
                    description = "Toggle touch controls",
                    opacity = overlayOpacity,
                    active = touchControlsEnabled,
                    view = view,
                ) { color ->
                    Icon(Icons.Filled.Menu, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                }
                if (effective) {
                    ToggleKey(
                        onClick = { onToggleArrowKeys(!arrowKeysVisible) },
                        description = "Toggle arrow keys",
                        opacity = overlayOpacity,
                        active = arrowKeysVisible,
                        view = view,
                    ) { color ->
                        Text("\u2194", color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Bottom-left: WASD cluster.
            AnimatedVisibility(
                visible = effective,
                modifier = Modifier.align(Alignment.BottomStart),
                enter = enter,
                exit = exit,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
                    Row {
                        Spacer(modifier = Modifier.width(KeySize + KeyGap))
                        HeldTextKey("W", KeyEvent.KEYCODE_W, onVirtualKey, overlayOpacity, view)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                        HeldTextKey("A", KeyEvent.KEYCODE_A, onVirtualKey, overlayOpacity, view)
                        HeldTextKey("S", KeyEvent.KEYCODE_S, onVirtualKey, overlayOpacity, view)
                        HeldTextKey("D", KeyEvent.KEYCODE_D, onVirtualKey, overlayOpacity, view)
                    }
                }
            }

            // Bottom-right: 1-2-3 above a wide Space bar.
            AnimatedVisibility(
                visible = effective,
                modifier = Modifier.align(Alignment.BottomEnd),
                enter = enter,
                exit = exit,
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(KeyGap),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                        HeldTextKey("1", KeyEvent.KEYCODE_1, onVirtualKey, overlayOpacity, view)
                        HeldTextKey("2", KeyEvent.KEYCODE_2, onVirtualKey, overlayOpacity, view)
                        HeldTextKey("3", KeyEvent.KEYCODE_3, onVirtualKey, overlayOpacity, view)
                    }
                    HeldTextKey(
                        label = "SPACE",
                        keyCode = KeyEvent.KEYCODE_SPACE,
                        onVirtualKey = onVirtualKey,
                        opacity = overlayOpacity,
                        view = view,
                        width = KeySize * 3 + KeyGap * 2,
                    )
                }
            }

            // Bottom-centre: arrow keys (optional).
            AnimatedVisibility(
                visible = effective && arrowKeysVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = enter,
                exit = exit,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                    HeldIconKey(Icons.Filled.KeyboardArrowLeft, "Left", KeyEvent.KEYCODE_DPAD_LEFT, onVirtualKey, overlayOpacity, view)
                    HeldIconKey(Icons.Filled.KeyboardArrowUp, "Up", KeyEvent.KEYCODE_DPAD_UP, onVirtualKey, overlayOpacity, view)
                    HeldIconKey(Icons.Filled.KeyboardArrowDown, "Down", KeyEvent.KEYCODE_DPAD_DOWN, onVirtualKey, overlayOpacity, view)
                    HeldIconKey(Icons.Filled.KeyboardArrowRight, "Right", KeyEvent.KEYCODE_DPAD_RIGHT, onVirtualKey, overlayOpacity, view)
                }
            }
        }
    }
}
