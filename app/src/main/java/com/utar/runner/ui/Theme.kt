@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.utar.runner.ui

import android.content.Context
import android.graphics.Color as AndroidColor
import android.content.res.Configuration
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.utar.runner.runtime.RunnerPrefs
import com.utar.runner.runtime.ThemeMode

/** Shared mutable theme state observed by UtarTheme and the Appearance panel. */
object ThemeState {
    var themeMode: ThemeMode by mutableStateOf(ThemeMode.System)
    var useDynamicColor: Boolean by mutableStateOf(true)
    var accentColorArgb: Int by mutableStateOf(0xFFE9C7A3.toInt())

    fun init(prefs: RunnerPrefs) {
        themeMode = prefs.themeMode
        useDynamicColor = prefs.useDynamicColor
        accentColorArgb = prefs.accentColorArgb
    }

    fun updateThemeMode(mode: ThemeMode, prefs: RunnerPrefs) {
        prefs.themeMode = mode
        themeMode = mode
    }

    fun updateUseDynamicColor(enabled: Boolean, prefs: RunnerPrefs) {
        prefs.useDynamicColor = enabled
        useDynamicColor = enabled
    }

    fun updateAccentColor(argb: Int, prefs: RunnerPrefs) {
        prefs.accentColorArgb = argb
        accentColorArgb = argb
        if (prefs.useDynamicColor) {
            prefs.useDynamicColor = false
            useDynamicColor = false
        }
    }
}

/** Accent color swatch the user can pick. */
data class AccentSwatch(
    val name: String,
    val argb: Int,
)

/** Built-in accent palette (warm, earthy tones that suit Until Then). */
val ACCENT_SWATCHES = listOf(
    AccentSwatch("Sand", 0xFFE9C7A3.toInt()),
    AccentSwatch("Terracotta", 0xFFE07A5F.toInt()),
    AccentSwatch("Lavender", 0xFFC7C3FE.toInt()),
    AccentSwatch("Sage", 0xFFB7C9B7.toInt()),
    AccentSwatch("Ocean", 0xFF7EA8C9.toInt()),
    AccentSwatch("Rose", 0xFFD48CA0.toInt()),
    AccentSwatch("Mint", 0xFF98D8C8.toInt()),
    AccentSwatch("Amber", 0xFFFFB74D.toInt()),
    AccentSwatch("Plum", 0xFFC39BD3.toInt()),
    AccentSwatch("Slate", 0xFF90A4AE.toInt()),
)

/** Expressive Material 3 type scale: heavier display/title weights, tighter tracking. */
private val UtarTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 46.sp,
        lineHeight = 52.sp,
        letterSpacing = (-1.sp),
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 38.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 27.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.4.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.5.sp,
    ),
)

/** Material 3 Expressive shape scale: generous radii, with extra-large reserved for hero surfaces. */
private val UtarShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(44.dp),
)

/** Builds a Material 3 ColorScheme honoring dynamic color, user accent, and light/dark mode. */
fun utarColorScheme(
    context: Context,
    themeMode: ThemeMode,
    useDynamicColor: Boolean,
    accentColorArgb: Int,
): androidx.compose.material3.ColorScheme {
    val isNight = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val isDark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isNight
    }

    val dynamicScheme = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isDark) {
            androidx.compose.material3.dynamicDarkColorScheme(context)
        } else {
            androidx.compose.material3.dynamicLightColorScheme(context)
        }
    } else {
        null
    }

    val accent = Color(accentColorArgb)
    // Light surfaces need a deeper primary than the pastel swatch itself, or the
    // whole UI turns into low-contrast cream. Keep the hue, pull down the value.
    val primary = if (isDark) accent else deepTone(accent)
    val onPrimary = onPrimaryFor(primary)
    val secondary = hueShift(primary, degrees = 55f, saturationFactor = 0.7f)
    val tertiary = hueShift(primary, degrees = -55f, saturationFactor = 0.82f)

    return dynamicScheme ?: when (isDark) {
        true -> androidx.compose.material3.darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = containerFor(primary, dark = true),
            onPrimaryContainer = onContainerFor(primary, dark = true),
            secondary = secondary,
            onSecondary = onPrimaryFor(secondary),
            secondaryContainer = containerFor(secondary, dark = true),
            onSecondaryContainer = onContainerFor(secondary, dark = true),
            tertiary = tertiary,
            onTertiary = onPrimaryFor(tertiary),
            tertiaryContainer = containerFor(tertiary, dark = true),
            onTertiaryContainer = onContainerFor(tertiary, dark = true),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF3A0909),
            background = Color(0xFF0A0D13),
            onBackground = Color(0xFFE8E6E1),
            surface = Color(0xFF0F141C),
            onSurface = Color(0xFFE8E6E1),
            surfaceVariant = Color(0xFF161D28),
            onSurfaceVariant = Color(0xFFA9B2C0),
            outline = Color(0xFF3A4452),
            outlineVariant = Color(0xFF232B36),
            inverseSurface = Color(0xFFE8E6E1),
            inverseOnSurface = Color(0xFF0A0D13),
            inversePrimary = accent,
            surfaceDim = Color(0xFF080A0F),
            surfaceBright = Color(0xFF1E2936),
            surfaceContainerLowest = Color(0xFF07090D),
            surfaceContainerLow = Color(0xFF0F141C),
            surfaceContainer = Color(0xFF161D28),
            surfaceContainerHigh = Color(0xFF1E2936),
            surfaceContainerHighest = Color(0xFF263340),
        )

        false -> androidx.compose.material3.lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = containerFor(primary, dark = false),
            onPrimaryContainer = onContainerFor(primary, dark = false),
            secondary = secondary,
            onSecondary = onPrimaryFor(secondary),
            secondaryContainer = containerFor(secondary, dark = false),
            onSecondaryContainer = onContainerFor(secondary, dark = false),
            tertiary = tertiary,
            onTertiary = onPrimaryFor(tertiary),
            tertiaryContainer = containerFor(tertiary, dark = false),
            onTertiaryContainer = onContainerFor(tertiary, dark = false),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            background = Color(0xFFFDF8F2),
            onBackground = Color(0xFF1D1B16),
            surface = Color(0xFFFDF8F2),
            onSurface = Color(0xFF1D1B16),
            surfaceVariant = Color(0xFFEDE4DA),
            onSurfaceVariant = Color(0xFF5B544C),
            outline = Color(0xFF857C72),
            outlineVariant = Color(0xFFD6CCC0),
            inverseSurface = Color(0xFF32302A),
            inverseOnSurface = Color(0xFFF6F0E8),
            inversePrimary = hsvTone(accent, value = 0.84f, saturation = 0.55f),
            surfaceDim = Color(0xFFE0D6C8),
            surfaceBright = Color(0xFFFFFCF8),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF8F1E9),
            surfaceContainer = Color(0xFFF2E9DF),
            surfaceContainerHigh = Color(0xFFECE2D6),
            surfaceContainerHighest = Color(0xFFE6DACD),
        )
    }
}

/** Picks a light or dark on-primary text color based on luminance. */
private fun onPrimaryFor(primary: Color): Color =
    if (primary.luminance() > 0.5f) Color(0xFF000000) else Color(0xFFFFFFFF)

/** Darkens a pastel accent for the light-theme primary role without losing its hue. */
private fun deepTone(color: Color): Color = hsvTone(color, value = 0.58f, saturation = 1.1f)

/** Sets HSV value and scales saturation, preserving the accent's hue. */
private fun hsvTone(color: Color, value: Float, saturation: Float = 1f): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgb(), hsv)
    hsv[1] = (hsv[1] * saturation).coerceIn(0f, 1f)
    hsv[2] = value.coerceIn(0f, 1f)
    return Color(AndroidColor.HSVToColor(hsv))
}

/** Tinted container tone derived from the chosen accent. */
private fun containerFor(accent: Color, dark: Boolean): Color =
    if (dark) {
        hsvTone(accent, value = 0.30f, saturation = 0.65f)
    } else {
        hsvTone(accent, value = 0.94f, saturation = 0.95f)
    }

/** High-contrast content color for a [containerFor] tone. */
private fun onContainerFor(accent: Color, dark: Boolean): Color =
    if (dark) {
        hsvTone(accent, value = 0.92f, saturation = 0.55f)
    } else {
        hsvTone(accent, value = 0.28f, saturation = 0.95f)
    }

/** Rotates the hue so secondary/tertiary tones still move with the accent. */
private fun hueShift(color: Color, degrees: Float, saturationFactor: Float): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    hsv[1] = (hsv[1] * saturationFactor).coerceIn(0f, 1f)
    return Color(AndroidColor.HSVToColor(hsv))
}

/** Material 3 theme wrapper. Reads theme preferences and applies the color scheme. */
@Composable
fun UtarTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { RunnerPrefs(context.applicationContext) }
    remember { ThemeState.init(prefs) }
    val themeMode = ThemeState.themeMode
    val useDynamicColor = ThemeState.useDynamicColor
    val accentColorArgb = ThemeState.accentColorArgb
    // Rebuilding a (dynamic) color scheme on every recomposition is wasted work; only the inputs
    // below can change it. uiMode is a key so a system dark-mode flip is still picked up.
    val uiMode = LocalConfiguration.current.uiMode
    val scheme = remember(context, themeMode, useDynamicColor, accentColorArgb, uiMode) {
        utarColorScheme(context, themeMode, useDynamicColor, accentColorArgb)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = UtarTypography,
        shapes = UtarShapes,
        content = content,
    )
}

/** Appearance panel content: mode, Material You toggle and accent swatches. */
@Composable
fun ThemeSettings(
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { RunnerPrefs(context.applicationContext) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsLabel("Theme mode")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    ThemeModeChip(
                        label = mode.label(),
                        selected = ThemeState.themeMode == mode,
                        onClick = { ThemeState.updateThemeMode(mode, prefs) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Dynamic color",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Follow the Material You wallpaper palette",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = ThemeState.useDynamicColor,
                onCheckedChange = { ThemeState.updateUseDynamicColor(it, prefs) },
            )
        }

        AnimatedVisibility(
            visible = !ThemeState.useDynamicColor,
            enter = expandVertically(gentleSpring()) + fadeIn(),
            exit = shrinkVertically(gentleSpring()) + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsLabel("Accent color")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ACCENT_SWATCHES.forEach { swatch ->
                        AccentSwatchItem(
                            swatch = swatch,
                            selected = swatch.argb == ThemeState.accentColorArgb,
                            onClick = { ThemeState.updateAccentColor(swatch.argb, prefs) },
                        )
                    }
                }
            }
        }

        TextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.End),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text("Done", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.System -> "System"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ThemeModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    BouncySurface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        pressedScale = 0.93f,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(vertical = 11.dp),
        )
    }
}

@Composable
private fun AccentSwatchItem(
    swatch: AccentSwatch,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale by bouncyScale(interaction, pressedScale = 0.88f)
    val color = Color(swatch.argb)
    val ring = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(ring)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                contentDescription = swatch.name
                this.selected = selected
            }
            .padding(if (selected) 3.dp else 1.5.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
