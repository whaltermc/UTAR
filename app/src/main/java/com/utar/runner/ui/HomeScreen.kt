package com.utar.runner.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.utar.runner.GameActivity
import com.utar.runner.runtime.Backend
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.install(uri, queryDisplayName(context, uri))
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportLog(uri)
    }

    LaunchedEffect(state.notice, state.error) {
        if (state.notice != null || state.error != null) {
            delay(8000)
            viewModel.dismissMessages()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "UNTIL THEN",
            fontFamily = FontFamily.Serif,
            fontSize = 40.sp,
            letterSpacing = 8.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "ANDROID RUNNER",
            letterSpacing = 3.sp,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Godot 4.1.4 runtime · ${state.backend.label} backend",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= 720.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GamePanel(
                        state = state,
                        modifier = Modifier.weight(1f),
                        onInstall = { importLauncher.launch(arrayOf("*/*")) },
                        onLaunch = {
                            state.packFile?.let { pack ->
                                context.startActivity(GameActivity.createIntent(context, pack, state.backend))
                            }
                        },
                    )
                    RuntimePanel(
                        state = state,
                        modifier = Modifier.weight(1f),
                        onSelectBackend = viewModel::selectBackend,
                        onExportLog = { exportLauncher.launch("utar-log-${System.currentTimeMillis()}.txt") },
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    GamePanel(
                        state = state,
                        modifier = Modifier.fillMaxWidth(),
                        onInstall = { importLauncher.launch(arrayOf("*/*")) },
                        onLaunch = {
                            state.packFile?.let { pack ->
                                context.startActivity(GameActivity.createIntent(context, pack, state.backend))
                            }
                        },
                    )
                    RuntimePanel(
                        state = state,
                        modifier = Modifier.fillMaxWidth(),
                        onSelectBackend = viewModel::selectBackend,
                        onExportLog = { exportLauncher.launch("utar-log-${System.currentTimeMillis()}.txt") },
                    )
                }
            }
        }

        val message = state.error ?: state.notice
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun GamePanel(
    state: HomeViewModel.UiState,
    modifier: Modifier,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
) {
    Panel(modifier = modifier, eyebrow = "Game package", title = "Until Then") {
        Text(
            text = if (state.packFile != null) {
                "${state.packName} · ${formatBytes(state.packBytes)}"
            } else {
                "No package installed yet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (state.patchedExtensions > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${state.patchedExtensions} extension config(s) rebuilt for Android",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Spacer(Modifier.height(18.dp))

        Button(onClick = onInstall, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.packFile == null) "Install PCK" else "Install a different PCK")
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = onLaunch,
            enabled = !state.busy && state.packFile != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Launch")
        }

        if (state.busy) {
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.stage.ifBlank { "Working…" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuntimePanel(
    state: HomeViewModel.UiState,
    modifier: Modifier,
    onSelectBackend: (Backend) -> Unit,
    onExportLog: () -> Unit,
) {
    Panel(modifier = modifier, eyebrow = "Runtime", title = "Graphics backend") {
        Text(
            text = "Applied as engine flags on the next launch. Auto leaves the game's own rendering setting alone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))

        Backend.entries.forEach { backend ->
            BackendOption(
                backend = backend,
                selected = backend == state.backend,
                enabled = !state.busy,
                onSelect = { onSelectBackend(backend) },
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = state.backend.engineArgs.joinToString(" ").ifEmpty { "no rendering flags" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        OutlinedButton(onClick = onExportLog, modifier = Modifier.fillMaxWidth()) {
            Text("Export latest log")
        }
    }
}

@Composable
private fun BackendOption(
    backend: Backend,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text = backend.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = backend.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Panel(
    modifier: Modifier,
    eyebrow: String,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(20.dp),
    ) {
        Text(
            text = eyebrow.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
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
