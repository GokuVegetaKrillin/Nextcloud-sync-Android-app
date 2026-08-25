package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.data.model.AccountEntity
import com.example.data.model.ConflictEntity
import com.example.data.model.SyncActivityEntity
import com.example.data.model.SyncFolderConfigEntity
import com.example.data.model.SyncSettingsEntity
import com.example.data.model.SyncStatus
import com.example.sync.SyncProgressState
import com.example.ui.MainViewModel
import com.example.ui.components.ActivityTypeBadge
import com.example.ui.components.FormatUtils
import com.example.ui.components.SyncStatusBadge
import com.example.ui.theme.*
import com.example.util.BatteryOptimizationHelper

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToFolders: () -> Unit,
    onNavigateToConflicts: () -> Unit,
    onNavigateToActivity: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val syncState by viewModel.syncState.collectAsState()
    val account by viewModel.account.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val activities by viewModel.activities.collectAsState()
    val conflicts by viewModel.conflicts.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val serverStatus by viewModel.serverConnectionStatus.collectAsState()
    val isTestingConnection by viewModel.isTestingConnection.collectAsState()
    val isBatteryOptimizationIgnored by viewModel.batteryOptimizationIgnored.collectAsState()
    val isNotificationPermissionGranted by viewModel.notificationPermissionGranted.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAllSystemStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "sync_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // 1. Nextcloud Server Connection Banner
        item {
            ServerConnectionCard(
                account = account,
                serverStatus = serverStatus,
                isTestingConnection = isTestingConnection,
                onRefreshConnection = { viewModel.checkConnection() },
                onEditServer = onNavigateToSettings
            )
        }

        // 2. Main Sync Hero Card
        item {
            SyncHeroCard(
                syncState = syncState,
                rotation = rotation,
                onSyncNow = { viewModel.startSync() },
                onPauseSync = { viewModel.pauseSync() },
                onResumeSync = { viewModel.resumeSync() },
                onCancelSync = { viewModel.cancelSync() }
            )
        }

        // Background Persistence & Memory Protection Alert (if system could kill app)
        if (!isBatteryOptimizationIgnored || !isNotificationPermissionGranted) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = NcWarningAmber.copy(alpha = 0.12f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NcWarningAmber.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().testTag("background_kill_warning_banner")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.BatterySaver,
                                contentDescription = null,
                                tint = NcWarningAmber,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable Background Persistence",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = NcWarningAmber
                                )
                                Text(
                                    text = if (!isBatteryOptimizationIgnored)
                                        "Android may stop background sync when you open other apps. Set battery to 'Unrestricted'."
                                    else
                                        "Allow notifications so the sync daemon stays alive in the background.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (!isBatteryOptimizationIgnored) {
                                Button(
                                    onClick = { BatteryOptimizationHelper.requestIgnoreBatteryOptimization(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = NcWarningAmber),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Unrestrict Battery", color = Color.Black, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                }
                            } else if (!isNotificationPermissionGranted) {
                                Button(
                                    onClick = { BatteryOptimizationHelper.openNotificationSettings(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Allow Notifications", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Conflict Alert Banner (if any)
        if (conflicts.isNotEmpty()) {
            item {
                ConflictAlertBanner(
                    conflicts = conflicts,
                    onResolveClick = onNavigateToConflicts
                )
            }
        }

        // 4. Background Sync & Schedule Info
        item {
            BackgroundSyncScheduleCard(
                settings = settings,
                isBatteryUnrestricted = isBatteryOptimizationIgnored,
                onSettingsClick = onNavigateToSettings
            )
        }

        // 5. Cloud Storage Quota Card
        item {
            StorageQuotaCard(account = account)
        }

        // 6. Quick Stats / Synced Folders Summary
        item {
            SyncedFoldersSummaryCard(
                folders = folders,
                onManageFolders = onNavigateToFolders
            )
        }

        // 7. Recent Sync Activity Preview
        item {
            RecentActivityCard(
                activities = activities.take(4),
                onViewAll = onNavigateToActivity
            )
        }
    }
}

@Composable
private fun ServerConnectionCard(
    account: AccountEntity?,
    serverStatus: com.example.data.model.ServerStatus?,
    isTestingConnection: Boolean,
    onRefreshConnection: () -> Unit,
    onEditServer: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth().testTag("server_connection_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(NcPrimaryBlue, NcCyanAccent)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cloud,
                        contentDescription = "Nextcloud Server",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = account?.serverUrl ?: "https://cloud.example.com",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isConnected = serverStatus?.isConnected ?: true
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) NcSuccessGreen else NcErrorRed)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isTestingConnection) "Checking connection..."
                            else if (isConnected) "${account?.username ?: "User"} • Online"
                            else "Disconnected • Tap settings to edit",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row {
                IconButton(
                    onClick = onRefreshConnection,
                    modifier = Modifier.testTag("refresh_connection_btn")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh connection status",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onEditServer,
                    modifier = Modifier.testTag("edit_server_url_btn")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Server settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncHeroCard(
    syncState: SyncProgressState,
    rotation: Float,
    onSyncNow: () -> Unit,
    onPauseSync: () -> Unit,
    onResumeSync: () -> Unit,
    onCancelSync: () -> Unit
) {
    val isSyncing = syncState.status == SyncStatus.SYNCING

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSyncing) NcPrimaryBlue.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth().testTag("sync_hero_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSyncing) NcPrimaryBlue else MaterialTheme.colorScheme.primaryContainer
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = "Sync state",
                            tint = if (isSyncing) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .size(28.dp)
                                .then(if (isSyncing) Modifier.rotate(rotation) else Modifier)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = if (isSyncing) "Synchronizing Changes" else "Bidirectional Sync",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Last sync: ${FormatUtils.formatTimestamp(syncState.lastSyncTimestamp)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                SyncStatusBadge(status = syncState.status)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action or progress detail
            if (isSyncing) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = syncState.currentAction,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (syncState.speedKbps > 0) {
                            Text(
                                text = FormatUtils.formatSpeed(syncState.speedKbps),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = NcPrimaryBlue
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val progress = if (syncState.totalFilesCount > 0) {
                        syncState.filesSyncedCount.toFloat() / syncState.totalFilesCount.toFloat()
                    } else 0.1f

                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = NcPrimaryBlue,
                        trackColor = NcLightBlue
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${syncState.filesSyncedCount} of ${syncState.totalFilesCount} files processed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = FormatUtils.formatBytes(syncState.bytesTransferred),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = syncState.currentAction,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Controls buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isSyncing) {
                    Button(
                        onClick = onPauseSync,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NcWarningAmber,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).testTag("pause_sync_btn")
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pause")
                    }

                    OutlinedButton(
                        onClick = onCancelSync,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).testTag("cancel_sync_btn")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel")
                    }
                } else if (syncState.isPaused) {
                    Button(
                        onClick = onResumeSync,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NcPrimaryBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).testTag("resume_sync_btn")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Resume Sync")
                    }
                } else {
                    Button(
                        onClick = onSyncNow,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NcPrimaryBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("sync_now_btn")
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = "Sync", modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sync Now", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictAlertBanner(
    conflicts: List<ConflictEntity>,
    onResolveClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NcWarningAmber.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth().clickable { onResolveClick() }.testTag("conflict_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Conflict",
                    tint = NcWarningAmber,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "${conflicts.size} Sync Conflict(s) Detected",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Both local and server copies were modified. Tap to resolve.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Resolve",
                tint = NcWarningAmber
            )
        }
    }
}

@Composable
private fun BackgroundSyncScheduleCard(
    settings: SyncSettingsEntity?,
    isBatteryUnrestricted: Boolean = true,
    onSettingsClick: () -> Unit
) {
    val intervalVal = settings?.syncIntervalValue ?: 15
    val intervalUnit = settings?.syncIntervalUnit?.name?.lowercase() ?: "minutes"
    val isBgActive = settings?.runInBackground ?: true
    val nextScheduled = settings?.nextScheduledSyncTimestamp ?: 0L

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().testTag("background_sync_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = "Sync schedule",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Sync Interval: Every $intervalVal $intervalUnit",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isBgActive) "Next sync: ${FormatUtils.formatScheduledTime(nextScheduled)}" else "Background sync disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.testTag("change_interval_btn")
                ) {
                    Text("Settings", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Daemon status indicator tag
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isBgActive && isBatteryUnrestricted) NcSuccessGreen.copy(alpha = 0.1f)
                        else NcWarningAmber.copy(alpha = 0.12f)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isBgActive && isBatteryUnrestricted) NcSuccessGreen else NcWarningAmber)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isBgActive && isBatteryUnrestricted) "Persistent Daemon: Active (Memory Protected)"
                    else if (isBgActive) "Persistent Daemon: Running (Battery Optimization Active)"
                    else "Background Daemon: Disabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isBgActive && isBatteryUnrestricted) NcSuccessGreen else NcWarningAmber
                )
            }
        }
    }
}

@Composable
private fun StorageQuotaCard(account: AccountEntity?) {
    val usedBytes = account?.quotaUsedBytes ?: (12L * 1024 * 1024 * 1024)
    val totalBytes = account?.quotaTotalBytes ?: (100L * 1024 * 1024 * 1024)
    val freeBytes = (totalBytes - usedBytes).coerceAtLeast(0L)
    val quotaProgress = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("quota_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Storage,
                        contentDescription = "Storage",
                        tint = NcPrimaryBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Nextcloud Storage Quota",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Text(
                    text = "${(quotaProgress * 100).toInt()}% Used",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = NcPrimaryBlue
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { quotaProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                color = NcPrimaryBlue,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Used: ${FormatUtils.formatBytes(usedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Free: ${FormatUtils.formatBytes(freeBytes)} of ${FormatUtils.formatBytes(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SyncedFoldersSummaryCard(
    folders: List<SyncFolderConfigEntity>,
    onManageFolders: () -> Unit
) {
    val enabledCount = folders.count { it.isSelected }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("folders_summary_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Selective Folder Sync",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$enabledCount of ${folders.size} folder(s) synchronized",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = onManageFolders,
                    modifier = Modifier.testTag("manage_folders_btn")
                ) {
                    Text("Manage", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                folders.take(4).forEach { folder ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (folder.isSelected) NcPrimaryBlue.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = if (folder.isSelected) Icons.Filled.Folder else Icons.Outlined.FolderOff,
                                contentDescription = folder.displayName,
                                tint = if (folder.isSelected) NcPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = folder.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentActivityCard(
    activities: List<SyncActivityEntity>,
    onViewAll: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("recent_activity_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Sync Activity",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )

                TextButton(
                    onClick = onViewAll,
                    modifier = Modifier.testTag("view_all_activity_btn")
                ) {
                    Text("View All", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (activities.isEmpty()) {
                Text(
                    text = "No sync activity recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                activities.forEach { act ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ActivityTypeBadge(type = act.type)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = act.path.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = act.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = FormatUtils.formatTimestamp(act.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
