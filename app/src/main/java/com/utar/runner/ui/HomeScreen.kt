@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.utar.runner.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.utar.runner.runtime.Backend
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.install(uri, queryDisplayName(context, uri))
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportLog(uri)
    }

    LaunchedEffect(state.error, state.notice) {
        val message = state.error ?: state.notice
        if (message != null) {
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )
            viewModel.dismissMessages()
        }
    }

    var showAppearance by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                val twoPane = maxWidth >= 720.dp
                val pagePadding = if (twoPane) 20.dp else 16.dp

                val install = { importLauncher.launch(arrayOf("*/*")) }
                val exportLog = { exportLauncher.launch("utar-log-${System.currentTimeMillis()}.txt") }
                val launch: () -> Unit = { state.packFile?.let(viewModel::launchGame) }

                if (twoPane) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(pagePadding),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Reveal(
                            index = 0,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            HeroPanel(
                                state = state,
                                onLaunch = launch,
                                fillHeight = true,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Reveal(index = 1) {
                                PackageCard(state, onInstall = install, onExportLog = exportLog)
                            }
                            Reveal(index = 2) {
                                GraphicsCard(state, onSelectBackend = viewModel::selectBackend)
                            }
                            Reveal(index = 3) {
                                AppearanceCard(
                                    expanded = showAppearance,
                                    onToggle = { showAppearance = !showAppearance },
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(pagePadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Reveal(index = 0) {
                            HeroPanel(state = state, onLaunch = launch, fillHeight = false, modifier = Modifier.fillMaxWidth())
                        }
                        Reveal(index = 1) {
                            PackageCard(state, onInstall = install, onExportLog = exportLog)
                        }
                        Reveal(index = 2) {
                            GraphicsCard(state, onSelectBackend = viewModel::selectBackend)
                        }
                        Reveal(index = 3) {
                            AppearanceCard(
                                expanded = showAppearance,
                                onToggle = { showAppearance = !showAppearance },
                            )
                        }
                    }
                }
            }
        }

        LaunchOverlay(visible = state.launching)
    }
}

// ---------------------------------------------------------------------------------------------
// Launch screen
// ---------------------------------------------------------------------------------------------

/**
 * Shown while the game process boots. Godot blocks its own UI thread for the whole pack-indexing
 * phase, so this screen lives in the launcher process, where it keeps animating the entire time.
 */
@Composable
private fun LaunchOverlay(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(250)),
    ) {
        var elapsed by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                elapsed += 1
            }
        }
        val blockTouches = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(interactionSource = blockTouches, indication = null, onClick = {}),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExpressiveLoader(size = 120.dp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Starting Until Then",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Indexing game data · " + String.format(Locale.US, "%d:%02d", elapsed / 60, elapsed % 60),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "The first launch can take about a minute while the engine reads the pack.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Hero
// ---------------------------------------------------------------------------------------------

@Composable
private fun HeroPanel(
    state: HomeViewModel.UiState,
    onLaunch: () -> Unit,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val scheme = MaterialTheme.colorScheme
    val installed = state.packFile != null

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(scheme.primaryContainer)
            .padding(24.dp),
    ) {
        Column(modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            Column(
                modifier = if (fillHeight) {
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scroll)
                } else {
                    Modifier
                },
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ExpressiveLoader(
                        size = 64.dp,
                        container = scheme.primary.copy(alpha = 0.16f),
                        color = scheme.primary,
                    )
                    Column {
                        Text(
                            text = "Until Then",
                            style = MaterialTheme.typography.headlineLarge,
                            color = scheme.onPrimaryContainer,
                            maxLines = 1,
                        )
                        Text(
                            text = "Android Runner",
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                    }
                }

                StatusChip(state = state, installed = installed)

                Text(
                    text = if (installed) {
                        "${state.packName.ifBlank { "Until Then" }} · ${formatBytes(state.packBytes)}"
                    } else {
                        "Choose your Until Then .pck to begin."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (state.busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = scheme.primary,
                            trackColor = scheme.primary.copy(alpha = 0.18f),
                        )
                        Text(
                            text = state.stage.ifBlank { "Preparing package…" },
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            ExpressiveButton(
                onClick = onLaunch,
                enabled = installed && !state.busy && !state.launching,
                container = scheme.primary,
                content = scheme.onPrimary,
                height = 64.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Play", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun StatusChip(state: HomeViewModel.UiState, installed: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when {
        state.launching || state.busy -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        installed -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    val label = when {
        state.launching -> "Starting the engine"
        state.busy -> "Installing package"
        installed -> "Ready to play"
        else -> "No game package"
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(content),
        )
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

@Composable
private fun PackageCard(
    state: HomeViewModel.UiState,
    onInstall: () -> Unit,
    onExportLog: () -> Unit,
) {
    val installed = state.packFile != null
    ExpressiveCard {
        SectionHeader(
            icon = Icons.Filled.Add,
            title = "Game package",
            subtitle = if (installed && state.patchedExtensions > 0) {
                "${state.patchedExtensions} extension config(s) rebuilt for Android"
            } else {
                "The runner never ships game files — bring your own .pck"
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExpressiveButton(
                onClick = onInstall,
                enabled = !state.busy && !state.launching,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                height = 48.dp,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (installed) "Change PCK" else "Choose PCK",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
            ExpressiveButton(
                onClick = onExportLog,
                enabled = !state.launching,
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurface,
                height = 48.dp,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export log", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GraphicsCard(
    state: HomeViewModel.UiState,
    onSelectBackend: (Backend) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ExpressiveCard {
        SectionHeader(
            icon = Icons.Filled.Build,
            title = "Graphics",
            subtitle = "Applied on the next launch",
        )
        // Connected button group: one shared row, the selected option morphs to a full pill.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Backend.entries.forEach { backend ->
                BackendOption(
                    backend = backend,
                    selected = backend == state.backend,
                    enabled = !state.busy && !state.launching,
                    onClick = { onSelectBackend(backend) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = state.backend.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        CodePill(text = state.backend.engineArgs.joinToString(" ").ifEmpty { "engine defaults" })
    }
}

@Composable
private fun BackendOption(
    backend: Backend,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(
        targetValue = if (selected) 24.dp else 12.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "optionCorner",
    )
    val container by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.surfaceContainerHighest,
        animationSpec = tween(180),
        label = "optionContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
        animationSpec = tween(180),
        label = "optionContent",
    )
    val shape = RoundedCornerShape(corner)
    Row(
        modifier = modifier
            .height(48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(shape)
            .background(container)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = backend.label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
private fun AppearanceCard(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "chevron",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(
                    icon = Icons.Filled.Settings,
                    container = scheme.tertiaryContainer,
                    tint = scheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                    Text(
                        "Theme, dynamic color, accents",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse appearance" else "Expand appearance",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { rotationZ = chevron },
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)) + fadeIn(),
                exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ThemeSettings(onClose = onToggle)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------

@Composable
private fun ExpressiveCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

/**
 * Button whose corners spring from a rounded rectangle to a full pill while it is pressed, the
 * Material 3 Expressive press behaviour. Built on the stock [Button], so ripple, disabled
 * styling and accessibility come from Material.
 */
@Composable
private fun ExpressiveButton(
    onClick: () -> Unit,
    container: Color,
    content: Color,
    height: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) height / 2 else 20.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "buttonCorner",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "buttonScale",
    )
    val shape: Shape = RoundedCornerShape(corner)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = shape,
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) {
        label()
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon = icon)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IconBadge(
    icon: ImageVector,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun CodePill(text: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

private fun formatBytes(bytes: Long): String {
    val megabyte = 1024.0 * 1024.0
    val gigabyte = megabyte * 1024.0
    return when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / gigabyte)
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / megabyte)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
