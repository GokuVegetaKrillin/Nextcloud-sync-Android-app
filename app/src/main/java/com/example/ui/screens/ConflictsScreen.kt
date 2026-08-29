package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ConflictEntity
import com.example.data.repository.ConflictResolution
import com.example.ui.MainViewModel
import com.example.ui.components.FormatUtils
import com.example.ui.theme.*

@Composable
fun ConflictsScreen(
    viewModel: MainViewModel
) {
    val conflicts by viewModel.conflicts.collectAsState()
    val resolvingConflicts by viewModel.resolvingConflicts.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Conflict Resolution Center",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = "Resolve synchronization discrepancies between device and server",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (conflicts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "No conflicts",
                        tint = NcSuccessGreen,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Unresolved Conflicts",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "All files are cleanly synchronized with your Nextcloud server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(conflicts, key = { it.remotePath }) { conflict ->
                    val isResolving = resolvingConflicts.contains(conflict.remotePath)
                    ConflictCard(
                        conflict = conflict,
                        isResolving = isResolving,
                        onResolve = { resolution ->
                            viewModel.resolveConflict(conflict.remotePath, resolution)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(
    conflict: ConflictEntity,
    isResolving: Boolean,
    onResolve: (ConflictResolution) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth().testTag("conflict_card_${conflict.remotePath}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Conflict",
                    tint = NcWarningAmber,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = conflict.remotePath.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Path: ${conflict.remotePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isResolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = NcPrimaryBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Side-by-side comparison
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Local version
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Smartphone, contentDescription = null, modifier = Modifier.size(16.dp), tint = NcPrimaryBlue)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("On Device (Local)", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Size: ${FormatUtils.formatBytes(conflict.localSize)}", style = MaterialTheme.typography.bodySmall)
                        Text("Modified: ${FormatUtils.formatTimestampWithYear(conflict.localMtime)}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Server version
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(16.dp), tint = NcCyanAccent)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Nextcloud Server", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Size: ${FormatUtils.formatBytes(conflict.remoteSize)}", style = MaterialTheme.typography.bodySmall)
                        Text("Modified: ${FormatUtils.formatTimestampWithYear(conflict.remoteMtime)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isResolving) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = NcPrimaryBlue.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NcPrimaryBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Applying resolution & syncing timestamps...", style = MaterialTheme.typography.bodySmall, color = NcPrimaryBlue)
                    }
                }
            } else {
                Text(
                    text = "Choose resolution action:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { onResolve(ConflictResolution.KEEP_LOCAL) },
                        enabled = !isResolving,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).testTag("keep_local_btn")
                    ) {
                        Text("Keep Local", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = { onResolve(ConflictResolution.KEEP_REMOTE) },
                        enabled = !isResolving,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).testTag("keep_remote_btn")
                    ) {
                        Text("Keep Server", style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = { onResolve(ConflictResolution.KEEP_BOTH) },
                        enabled = !isResolving,
                        colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).testTag("keep_both_btn")
                    ) {
                        Text("Keep Both", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
