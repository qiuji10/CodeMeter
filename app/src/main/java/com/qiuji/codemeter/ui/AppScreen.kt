package com.qiuji.codemeter.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qiuji.codemeter.model.AppSettings
import com.qiuji.codemeter.model.Profile
import com.qiuji.codemeter.model.ProviderId
import com.qiuji.codemeter.model.ProviderUsage
import com.qiuji.codemeter.model.ResetDisplayMode
import com.qiuji.codemeter.model.UsageDisplayMode
import com.qiuji.codemeter.model.UsageSnapshot
import com.qiuji.codemeter.model.UsageWindow
import com.qiuji.codemeter.model.canStartSessionWindow
import com.qiuji.codemeter.util.TimeFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val ClaudeOrange = Color(0xFFF59E0B)
private val CodexBlue = Color(0xFF3B82F6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showReorderProfiles by rememberSaveable { mutableStateOf(false) }
    var showAddProfile by rememberSaveable { mutableStateOf(false) }
    var renameProfile by remember { mutableStateOf<Profile?>(null) }
    var removeProfile by remember { mutableStateOf<Profile?>(null) }
    var startSessionProfile by remember { mutableStateOf<Profile?>(null) }

    BackHandler(enabled = showReorderProfiles) { showReorderProfiles = false }
    BackHandler(enabled = showSettings && !showReorderProfiles) { showSettings = false }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(
        lifecycleOwner,
        state.settings.autoRefreshEnabled,
        state.settings.autoRefreshIntervalMinutes,
        state.connected,
    ) {
        if (!state.settings.autoRefreshEnabled || state.connected.isEmpty()) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(state.settings.autoRefreshIntervalMinutes.coerceAtLeast(1) * 60_000L)
                vm.refreshAll()
            }
        }
    }

    if (showReorderProfiles) {
        ReorderProfilesPage(
            profiles = state.profiles,
            onBack = { showReorderProfiles = false },
            onReorder = vm::reorderProfiles,
        )
    } else if (showSettings) {
        SettingsPage(
            settings = state.settings,
            profileCount = state.profiles.size,
            onBack = { showSettings = false },
            onReorderProfiles = { showReorderProfiles = true },
            onUsageDisplayMode = vm::setUsageDisplayMode,
            onNearExhaustedNotificationsEnabled = vm::setNearExhaustedNotificationsEnabled,
            onNearExhaustedThreshold = vm::setNearExhaustedThreshold,
            onResetNotificationsEnabled = vm::setResetNotificationsEnabled,
            onCompactView = vm::setCompactView,
            onResetDisplayMode = vm::setResetDisplayMode,
            onShowHistory = vm::setShowHistory,
            onAutoRefreshEnabled = vm::setAutoRefreshEnabled,
            onBackgroundFetchEnabled = vm::setBackgroundFetchEnabled,
            onAutoRefreshIntervalMinutes = vm::setAutoRefreshIntervalMinutes,
        )
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("CodeMeter", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (state.profiles.isEmpty()) "No profiles" else "${state.profiles.size} profile${if (state.profiles.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showAddProfile = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add profile")
                        }
                        IconButton(onClick = vm::refreshAll, enabled = state.connected.isNotEmpty()) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh all")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (state.settings.compactView) 8.dp else 14.dp),
            ) {
                if (!state.settings.compactView) NoticeCard()

                if (state.profiles.isEmpty()) {
                    EmptyProfilesCard(onAddProfile = { showAddProfile = true })
                } else {
                    state.profiles.forEach { profile ->
                        ProfileCard(
                            profile = profile,
                            connected = profile.id in state.connected,
                            refreshing = profile.id in state.refreshing,
                            usage = state.usage[profile.id],
                            settings = state.settings,
                            onConnect = { vm.connect(profile.id) },
                            onRefresh = { vm.refresh(profile.id) },
                            onStartSession = { startSessionProfile = profile },
                            onRename = { renameProfile = profile },
                            onDisconnect = { vm.disconnect(profile.id) },
                            onRemove = { removeProfile = profile },
                        )
                    }
                }

                if (state.settings.showHistory && state.profiles.isNotEmpty()) {
                    HistoryCard(state.profiles, state.history, state.settings)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAddProfile) {
        AddProfileDialog(
            onAdd = { provider ->
                showAddProfile = false
                vm.addProfile(provider)
            },
            onDismiss = { showAddProfile = false },
        )
    }

    renameProfile?.let { profile ->
        RenameProfileDialog(
            profile = profile,
            onRename = { name ->
                vm.renameProfile(profile.id, name)
                renameProfile = null
            },
            onDismiss = { renameProfile = null },
        )
    }

    removeProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { removeProfile = null },
            title = { Text("Remove ${profile.name}?") },
            text = { Text("This removes the profile, its encrypted login tokens, and its local usage history from this phone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.removeProfile(profile.id)
                        removeProfile = null
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeProfile = null }) { Text("Cancel") } },
        )
    }

    startSessionProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { startSessionProfile = null },
            title = { Text("Start ${profile.name} session window?") },
            text = {
                Text(
                    "CodeMeter will send one tiny real ${profile.provider.displayName} request (\"Reply with hi\"). " +
                        "This consumes a small amount of quota and starts the normal session timer early."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.startSessionWindow(profile.id)
                        startSessionProfile = null
                    },
                ) { Text("Start session") }
            },
            dismissButton = { TextButton(onClick = { startSessionProfile = null }) { Text("Cancel") } },
        )
    }

    state.claudeLogin?.let { login ->
        val profile = state.profiles.firstOrNull { it.id == login.profileId }
        ClaudeLoginDialog(
            profileName = profile?.name ?: "Claude Code",
            url = login.authorizeUrl,
            code = login.code,
            busy = login.profileId in state.refreshing,
            onCodeChanged = vm::updateClaudeCode,
            onFinish = vm::finishClaudeLogin,
            onDismiss = vm::cancelClaudeLogin,
        )
    }

    state.codexLogin?.let { login ->
        val profile = state.profiles.firstOrNull { it.id == login.profileId }
        CodexLoginDialog(
            profileName = profile?.name ?: "Codex",
            verificationUrl = login.deviceCode.verificationUrl,
            userCode = login.deviceCode.userCode,
            busy = login.profileId in state.refreshing,
            onFinish = vm::finishCodexLogin,
            onDismiss = vm::cancelCodexLogin,
        )
    }
}

@Composable
private fun NoticeCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            "Runs entirely on this phone. Tokens are encrypted with Android Keystore and usage history stays local.",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EmptyProfilesCard(onAddProfile: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No usage profiles yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Add a Claude Code or Codex account to start tracking its quota.", textAlign = TextAlign.Center)
            Button(onClick = onAddProfile) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add Profile")
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    connected: Boolean,
    refreshing: Boolean,
    usage: ProviderUsage?,
    settings: AppSettings,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onStartSession: () -> Unit,
    onRename: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val cardPadding = if (settings.compactView) 12.dp else 16.dp
    val spacing = if (settings.compactView) 8.dp else 12.dp

    Card(shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        profile.name,
                        style = if (settings.compactView) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        buildString {
                            append(profile.provider.displayName)
                            append(if (connected) " · Connected" else " · Not connected")
                            if (connected && usage?.plan != null) append(" · ${usage.plan.uppercase(Locale.US)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else if (connected) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh ${profile.name}")
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "${profile.name} options")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (connected) {
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = { menuExpanded = false; onRefresh() },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            )
                            if (usage?.canStartSessionWindow() == true) {
                                DropdownMenuItem(
                                    text = { Text("Start session window") },
                                    onClick = { menuExpanded = false; onStartSession() },
                                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menuExpanded = false; onRename() },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        )
                        if (connected) {
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                onClick = { menuExpanded = false; onDisconnect() },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Remove profile") },
                            onClick = { menuExpanded = false; onRemove() },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                        )
                    }
                }
            }

            if (!connected) {
                Button(onClick = onConnect, enabled = !refreshing, modifier = Modifier.fillMaxWidth()) {
                    Text("Connect ${profile.provider.displayName}")
                }
            } else if (usage == null) {
                Text("Connected. Refresh to load quota data.", style = MaterialTheme.typography.bodyMedium)
            } else {
                if (usage.windows.isEmpty() && usage.statusText != null) {
                    Text(
                        "No live quota data yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                usage.windows.forEachIndexed { index, window ->
                    if (index > 0) HorizontalDivider()
                    UsageWindowRow(window, profile.provider, settings)
                }
                usage.creditsText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val freshnessText = buildString {
                    if (usage.updatedAtEpochMs > 0L) append("Updated ${formatFreshness(usage.updatedAtEpochMs)}")
                    else append("No successful update yet")
                    usage.statusText?.let { status ->
                        append(" · $status")
                        usage.retryAtEpochMs?.takeIf { it > System.currentTimeMillis() }?.let { retryAt ->
                            append(" · retry in ${formatShortRemaining(retryAt)}")
                        }
                    }
                }
                Text(
                    freshnessText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (usage.isStale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsageWindowRow(window: UsageWindow, provider: ProviderId, settings: AppSettings) {
    val shownPercent = when (settings.usageDisplayMode) {
        UsageDisplayMode.USED -> window.usedPercent
        UsageDisplayMode.LEFT -> 100.0 - window.usedPercent
    }.coerceIn(0.0, 100.0)
    val fraction = (shownPercent / 100.0).toFloat()
    val color = providerColor(provider)
    val resetText = when (settings.resetDisplayMode) {
        ResetDisplayMode.REMAINING -> TimeFormat.remaining(window.resetsAtEpochMs)
        ResetDisplayMode.EXACT -> TimeFormat.exact(window.resetsAtEpochMs)
    }

    Column(verticalArrangement = Arrangement.spacedBy(if (settings.compactView) 4.dp else 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                window.label,
                modifier = Modifier.weight(1f),
                style = if (settings.compactView) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
            )
            Text(
                "${shownPercent.round1()}% ${if (settings.usageDisplayMode == UsageDisplayMode.LEFT) "left" else "used"}",
                fontWeight = FontWeight.SemiBold,
            )
        }
        UsageProgressBar(
            fraction = fraction,
            color = color,
        )
        Text(resetText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UsageProgressBar(fraction: Float, color: Color) {
    val progress = fraction.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(color.copy(alpha = 0.16f), shape),
    ) {
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .background(color, shape),
            )
        }
    }
}

@Composable
private fun HistoryCard(
    profiles: List<Profile>,
    history: Map<String, List<UsageSnapshot>>,
    settings: AppSettings,
) {
    val modeLabel = if (settings.usageDisplayMode == UsageDisplayMode.LEFT) "left" else "used"
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(if (settings.compactView) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (settings.compactView) 10.dp else 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "24h history",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Session · $modeLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            profiles.forEach { profile ->
                val points = history[profile.id]
                    .orEmpty()
                    .filter { it.label == "Session" }
                    .sortedBy { it.capturedAtEpochMs }

                HistoryProfileCard(
                    profile = profile,
                    points = points,
                    settings = settings,
                )
            }
        }
    }
}

@Composable
private fun HistoryProfileCard(
    profile: Profile,
    points: List<UsageSnapshot>,
    settings: AppSettings,
) {
    val color = providerColor(profile.provider)
    val compact = settings.compactView
    val latest = points.lastOrNull()
    val resetIndices = remember(points) { detectResetIndices(points) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 10.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (latest != null) {
                    val shown = historyDisplayPercent(latest.usedPercent, settings.usageDisplayMode)
                    Text(
                        "${shown.round1()}% ${if (settings.usageDisplayMode == UsageDisplayMode.LEFT) "left" else "used"}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                    )
                }
            }

            if (points.size < 2) {
                Text(
                    if (points.isEmpty()) "No history yet" else "Waiting for more samples",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                HistoryChart(
                    points = points,
                    provider = profile.provider,
                    settings = settings,
                    resetIndices = resetIndices,
                )
            }
        }
    }
}

@Composable
private fun HistoryChart(
    points: List<UsageSnapshot>,
    provider: ProviderId,
    settings: AppSettings,
    resetIndices: Set<Int>,
) {
    val lineColor = providerColor(provider)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val compact = settings.compactView
    val chartHeight = if (compact) 72.dp else 94.dp
    val now = remember(points) { System.currentTimeMillis() }
    val start = now - 24L * 60 * 60 * 1000
    val visiblePoints = points.filter { it.capturedAtEpochMs in start..now }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            // A fixed 0-100 scale keeps profiles comparable without a noisy labeled Y axis.
            listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
                val y = size.height * fraction
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }

            if (visiblePoints.isEmpty()) return@Canvas

            val path = Path()
            visiblePoints.forEachIndexed { index, point ->
                val x = ((point.capturedAtEpochMs - start).toFloat() / (now - start).toFloat())
                    .coerceIn(0f, 1f) * size.width
                val shown = historyDisplayPercent(point.usedPercent, settings.usageDisplayMode).toFloat()
                val y = size.height - (shown.coerceIn(0f, 100f) / 100f) * size.height
                val originalIndex = points.indexOf(point)
                if (index == 0 || originalIndex in resetIndices) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 3.5f, cap = StrokeCap.Round))

            visiblePoints.forEachIndexed { visibleIndex, point ->
                val originalIndex = points.indexOf(point)
                if (originalIndex in resetIndices) {
                    val x = ((point.capturedAtEpochMs - start).toFloat() / (now - start).toFloat())
                        .coerceIn(0f, 1f) * size.width
                    drawLine(
                        color = lineColor.copy(alpha = 0.45f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                }

                if (visibleIndex == visiblePoints.lastIndex) {
                    val x = ((point.capturedAtEpochMs - start).toFloat() / (now - start).toFloat())
                        .coerceIn(0f, 1f) * size.width
                    val shown = historyDisplayPercent(point.usedPercent, settings.usageDisplayMode).toFloat()
                    val y = size.height - (shown.coerceIn(0f, 100f) / 100f) * size.height
                    drawCircle(color = lineColor, radius = 5f, center = Offset(x, y))
                    drawCircle(color = surfaceColor, radius = 2f, center = Offset(x, y))
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("24h ago", style = MaterialTheme.typography.labelSmall, color = axisColor)
            Spacer(Modifier.weight(1f))
            if (resetIndices.isNotEmpty()) {
                Text(
                    if (resetIndices.size == 1) "1 reset" else "${resetIndices.size} resets",
                    style = MaterialTheme.typography.labelSmall,
                    color = axisColor,
                )
                Spacer(Modifier.weight(1f))
            }
            Text("Now", style = MaterialTheme.typography.labelSmall, color = axisColor)
        }
    }
}

private fun historyDisplayPercent(usedPercent: Double, mode: UsageDisplayMode): Double = when (mode) {
    UsageDisplayMode.USED -> usedPercent
    UsageDisplayMode.LEFT -> 100.0 - usedPercent
}.coerceIn(0.0, 100.0)

private fun detectResetIndices(points: List<UsageSnapshot>): Set<Int> {
    if (points.size < 2) return emptySet()
    val resets = linkedSetOf<Int>()
    for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]
        val usedDrop = previous.usedPercent - current.usedPercent
        val resetTimeAdvanced = previous.resetsAtEpochMs != null &&
            current.resetsAtEpochMs != null &&
            current.resetsAtEpochMs > previous.resetsAtEpochMs + 30L * 60 * 1000

        if (usedDrop >= 20.0 || (usedDrop >= 5.0 && resetTimeAdvanced)) {
            resets += index
        }
    }
    return resets
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReorderProfilesPage(
    profiles: List<Profile>,
    onBack: () -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val orderedProfiles = remember(profiles) {
        mutableStateListOf<Profile>().apply { addAll(profiles) }
    }
    var draggingProfileId by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val itemSpacingPx = with(density) { 8.dp.toPx() }
    val liftedShadowPx = with(density) { 18.dp.toPx() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reorder profiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "Long press, then drag. The lifted card follows your finger while the other profiles move out of the way.",
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(orderedProfiles, key = { _, profile -> profile.id }) { _, profile ->
                    var dragOffsetY by remember(profile.id) { mutableFloatStateOf(0f) }
                    var itemHeightPx by remember(profile.id) { mutableFloatStateOf(0f) }
                    val dragging = draggingProfileId == profile.id
                    val providerAccent = providerColor(profile.provider)
                    val renderedOffsetY by animateFloatAsState(
                        targetValue = if (dragging) dragOffsetY else 0f,
                        animationSpec = if (dragging) snap() else spring(),
                        label = "reorder-drag-offset",
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (dragging) 1.035f else 1f,
                        animationSpec = spring(),
                        label = "reorder-drag-scale",
                    )

                    // Keep the actively dragged item out of placement animation. Its visual position
                    // follows the pointer; only the surrounding items animate into their new slots.
                    val placementModifier = if (dragging) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.fillMaxWidth().animateItem()
                    }

                    Box(modifier = placementModifier) {
                        // A subtle placeholder remains in the item's logical slot while the real
                        // card is lifted. This makes the current drop position obvious.
                        if (dragging) {
                            Card(
                                modifier = Modifier
                                    .matchParentSize()
                                    .graphicsLayer { alpha = 0.22f },
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, providerAccent.copy(alpha = 0.7f)),
                                colors = CardDefaults.cardColors(
                                    containerColor = providerAccent.copy(alpha = 0.12f),
                                ),
                            ) {}
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (dragging) 2f else 0f)
                                .onSizeChanged { itemHeightPx = it.height.toFloat() }
                                .graphicsLayer {
                                    translationY = renderedOffsetY
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = if (dragging) 0.97f else 1f
                                    shadowElevation = if (dragging) liftedShadowPx else 0f
                                }
                                .pointerInput(profile.id, orderedProfiles.size, itemHeightPx) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingProfileId = profile.id
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            if (draggingProfileId == profile.id) {
                                                onReorder(orderedProfiles.map { it.id })
                                                draggingProfileId = null
                                            }
                                        },
                                        onDragEnd = {
                                            if (draggingProfileId == profile.id) {
                                                onReorder(orderedProfiles.map { it.id })
                                                draggingProfileId = null
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y

                                            val itemExtent = (itemHeightPx + itemSpacingPx).coerceAtLeast(1f)
                                            val switchThreshold = itemExtent * 0.5f
                                            var currentIndex = orderedProfiles.indexOfFirst { it.id == profile.id }

                                            // Reorder as soon as the dragged card crosses half of the
                                            // neighbouring slot. Adjusting the offset by one item extent
                                            // keeps the lifted card continuously underneath the finger.
                                            while (
                                                dragOffsetY > switchThreshold &&
                                                currentIndex >= 0 &&
                                                currentIndex < orderedProfiles.lastIndex
                                            ) {
                                                val moved = orderedProfiles.removeAt(currentIndex)
                                                orderedProfiles.add(currentIndex + 1, moved)
                                                dragOffsetY -= itemExtent
                                                currentIndex += 1
                                            }
                                            while (
                                                dragOffsetY < -switchThreshold &&
                                                currentIndex > 0
                                            ) {
                                                val moved = orderedProfiles.removeAt(currentIndex)
                                                orderedProfiles.add(currentIndex - 1, moved)
                                                dragOffsetY += itemExtent
                                                currentIndex -= 1
                                            }
                                        },
                                    )
                                },
                            shape = RoundedCornerShape(16.dp),
                            border = if (dragging) {
                                BorderStroke(1.dp, providerAccent.copy(alpha = 0.75f))
                            } else {
                                null
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (dragging) {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = "Drag ${profile.name}",
                                    tint = if (dragging) providerAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (dragging) "Release to place" else profile.provider.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (dragging) providerAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    settings: AppSettings,
    profileCount: Int,
    onBack: () -> Unit,
    onReorderProfiles: () -> Unit,
    onUsageDisplayMode: (UsageDisplayMode) -> Unit,
    onNearExhaustedNotificationsEnabled: (Boolean) -> Unit,
    onNearExhaustedThreshold: (Int) -> Unit,
    onResetNotificationsEnabled: (Boolean) -> Unit,
    onCompactView: (Boolean) -> Unit,
    onResetDisplayMode: (ResetDisplayMode) -> Unit,
    onShowHistory: (Boolean) -> Unit,
    onAutoRefreshEnabled: (Boolean) -> Unit,
    onBackgroundFetchEnabled: (Boolean) -> Unit,
    onAutoRefreshIntervalMinutes: (Int) -> Unit,
) {
    val context = LocalContext.current
    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
    }
    var refreshIntervalExpanded by remember { mutableStateOf(false) }
    val refreshIntervals = listOf(5, 10, 15, 30, 60)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard("Usage display") {
                Text("Choose what the percentages and progress bars represent.", style = MaterialTheme.typography.bodySmall)
                ChoiceButtons(
                    firstLabel = "Usage used",
                    secondLabel = "Usage left",
                    firstSelected = settings.usageDisplayMode == UsageDisplayMode.USED,
                    onFirst = { onUsageDisplayMode(UsageDisplayMode.USED) },
                    onSecond = { onUsageDisplayMode(UsageDisplayMode.LEFT) },
                )
            }

            SettingsCard("Refresh & fetch") {
                SettingSwitchRow(
                    title = "Auto refresh",
                    subtitle = "Periodically refresh connected profiles while CodeMeter is open.",
                    checked = settings.autoRefreshEnabled,
                    onCheckedChange = onAutoRefreshEnabled,
                )

                if (settings.autoRefreshEnabled) {
                    HorizontalDivider()
                    Text("Refresh interval", style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton(
                            onClick = { refreshIntervalExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Every ${settings.autoRefreshIntervalMinutes} minutes")
                        }
                        DropdownMenu(
                            expanded = refreshIntervalExpanded,
                            onDismissRequest = { refreshIntervalExpanded = false },
                        ) {
                            refreshIntervals.forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text("$minutes minutes") },
                                    onClick = {
                                        onAutoRefreshIntervalMinutes(minutes)
                                        refreshIntervalExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                SettingSwitchRow(
                    title = "Background fetch",
                    subtitle = "Fetch usage while CodeMeter is in the background so history and alerts can stay current.",
                    checked = settings.backgroundFetchEnabled,
                    onCheckedChange = onBackgroundFetchEnabled,
                )
                Text(
                    "Opening or returning to CodeMeter always refreshes once. Android limits periodic background work to 15 minutes or longer; the selected interval applies directly while the app is open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard("Notifications") {
                SettingSwitchRow(
                    title = "Nearly exhausted",
                    subtitle = "Notify once when a quota window reaches the selected usage level.",
                    checked = settings.nearExhaustedNotificationsEnabled,
                    onCheckedChange = { enabled ->
                        onNearExhaustedNotificationsEnabled(enabled)
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
                if (settings.nearExhaustedNotificationsEnabled) {
                    ThresholdSetting("Notify at", settings.nearExhaustedThreshold, 50f..100f) { onNearExhaustedThreshold(it) }
                    Text(
                        "Based on quota used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()
                SettingSwitchRow(
                    title = "Limit reset",
                    subtitle = "Notify when the current quota window ends and usage becomes available again.",
                    checked = settings.resetNotificationsEnabled,
                    onCheckedChange = { enabled ->
                        onResetNotificationsEnabled(enabled)
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )

                if ((settings.nearExhaustedNotificationsEnabled || settings.resetNotificationsEnabled) && !notificationGranted) {
                    Text("Android notification permission is currently off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Grant notification permission") }
                }

                TextButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        context.startActivity(intent)
                    },
                ) { Text("Open Android notification settings") }
            }

            SettingsCard("Profiles") {
                Text(
                    if (profileCount < 2) "Add at least two profiles to change their order." else "Long press and drag profiles into the order you want on the home screen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = onReorderProfiles,
                    enabled = profileCount >= 2,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DragHandle, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reorder profiles")
                }
            }

            SettingsCard("Display") {
                SettingSwitchRow(
                    title = "Compact view",
                    subtitle = "Use tighter cards and shorter history charts.",
                    checked = settings.compactView,
                    onCheckedChange = onCompactView,
                )
                HorizontalDivider()
                Text("Reset time", style = MaterialTheme.typography.titleSmall)
                ChoiceButtons(
                    firstLabel = "Remaining time",
                    secondLabel = "Exact date",
                    firstSelected = settings.resetDisplayMode == ResetDisplayMode.REMAINING,
                    onFirst = { onResetDisplayMode(ResetDisplayMode.REMAINING) },
                    onSecond = { onResetDisplayMode(ResetDisplayMode.EXACT) },
                )
                HorizontalDivider()
                SettingSwitchRow(
                    title = "Show 24-hour history",
                    subtitle = "Show local session-usage charts below the profiles.",
                    checked = settings.showHistory,
                    onCheckedChange = onShowHistory,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceButtons(
    firstLabel: String,
    secondLabel: String,
    firstSelected: Boolean,
    onFirst: () -> Unit,
    onSecond: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (firstSelected) {
            Button(onClick = onFirst, modifier = Modifier.weight(1f)) { Text(firstLabel) }
            OutlinedButton(onClick = onSecond, modifier = Modifier.weight(1f)) { Text(secondLabel) }
        } else {
            OutlinedButton(onClick = onFirst, modifier = Modifier.weight(1f)) { Text(firstLabel) }
            Button(onClick = onSecond, modifier = Modifier.weight(1f)) { Text(secondLabel) }
        }
    }
}

@Composable
private fun ThresholdSetting(label: String, value: Int, range: ClosedFloatingPointRange<Float>, onChange: (Int) -> Unit) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("$value%", fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range,
            steps = ((range.endInclusive - range.start) / 5f).toInt().coerceAtLeast(1) - 1,
        )
    }
}

@Composable
private fun AddProfileDialog(onAdd: (ProviderId) -> Unit, onDismiss: () -> Unit) {
    var provider by remember { mutableStateOf(ProviderId.CLAUDE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select the account type to connect.")
                ProviderId.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = provider == option, onClick = { provider = option })
                        TextButton(onClick = { provider = option }) { Text(option.displayName) }
                    }
                }
                Text(
                    "Multiple profiles of the same provider are supported. You can rename them from the card menu after adding them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(onClick = { onAdd(provider) }) { Text("Add & connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameProfileDialog(profile: Profile, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ClaudeLoginDialog(
    profileName: String,
    url: String,
    code: String,
    busy: Boolean,
    onCodeChanged: (String) -> Unit,
    onFinish: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Connect $profileName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. Open Claude authorization.\n2. Sign in with the account for this profile.\n3. Copy the authorization code shown by Claude and paste it here.")
                OutlinedButton(onClick = { openBrowser(context, url) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open Claude authorization")
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChanged,
                    label = { Text("Authorization code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }
        },
        confirmButton = {
            Button(onClick = onFinish, enabled = code.isNotBlank() && !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Connect")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun CodexLoginDialog(
    profileName: String,
    verificationUrl: String,
    userCode: String,
    busy: Boolean,
    onFinish: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Connect $profileName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Open the OpenAI device-login page, sign in with the account for this profile, then enter this code:", textAlign = TextAlign.Start)
                Text(userCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyText(context, "Codex code", userCode) }, modifier = Modifier.weight(1f)) {
                        Text("Copy code")
                    }
                    OutlinedButton(onClick = { openBrowser(context, verificationUrl) }, modifier = Modifier.weight(1f)) {
                        Text("Open browser")
                    }
                }
                Text("After approval, return here and tap Finish.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = onFinish, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Finish")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

private fun providerColor(provider: ProviderId): Color = when (provider) {
    ProviderId.CLAUDE -> ClaudeOrange
    ProviderId.CODEX -> CodexBlue
}

private fun openBrowser(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun Double.round1(): String = if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(Locale.US, this)

private fun formatFreshness(epochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): String {
    val deltaSeconds = ((nowEpochMs - epochMs).coerceAtLeast(0L) / 1000L)
    return when {
        deltaSeconds < 60 -> "now"
        deltaSeconds < 3600 -> "${deltaSeconds / 60}m ago"
        deltaSeconds < 86_400 -> "${deltaSeconds / 3600}h ago"
        else -> DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))
    }
}

private fun formatShortRemaining(targetEpochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): String {
    val totalMinutes = ((targetEpochMs - nowEpochMs).coerceAtLeast(0L) + 59_999L) / 60_000L
    return when {
        totalMinutes < 60 -> "${totalMinutes}m"
        totalMinutes < 24 * 60 -> "${totalMinutes / 60}h ${totalMinutes % 60}m"
        else -> "${totalMinutes / (24 * 60)}d ${totalMinutes % (24 * 60) / 60}h"
    }
}
