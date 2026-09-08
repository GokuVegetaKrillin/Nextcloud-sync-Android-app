package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ActivityType
import com.example.data.model.SyncActivityEntity
import com.example.ui.MainViewModel
import com.example.ui.components.ActivityTypeBadge
import com.example.ui.components.FormatUtils
import com.example.ui.theme.*

@Composable
fun ActivityScreen(
    viewModel: MainViewModel
) {
    val activities by viewModel.activities.collectAsState()
    var selectedFilterCategory by remember { mutableStateOf<String?>(null) }

    val filteredActivities = remember(activities, selectedFilterCategory) {
        when (selectedFilterCategory) {
            null -> activities
            "UPLOADS" -> activities.filter { it.type == ActivityType.UPLOAD }
            "DOWNLOADS" -> activities.filter { it.type == ActivityType.DOWNLOAD }
            "DELETIONS" -> activities.filter { it.type == ActivityType.DELETE_LOCAL || it.type == ActivityType.DELETE_REMOTE }
            "CONFLICTS" -> activities.filter { it.type == ActivityType.CONFLICT_DETECTED || it.type == ActivityType.CONFLICT_RESOLVED }
            "ERRORS" -> activities.filter { it.type == ActivityType.ERROR }
            else -> activities
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Header & Clear Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Sync Activity Log",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "${activities.size} journal events recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (activities.isNotEmpty()) {
                IconButton(
                    onClick = { viewModel.clearActivities() },
                    modifier = Modifier.testTag("clear_activities_btn")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = "Clear activities",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Filter chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().testTag("activity_filter_row")
        ) {
            item {
                FilterChip(
                    selected = selectedFilterCategory == null,
                    onClick = { selectedFilterCategory = null },
                    label = { Text("All (${activities.size})") }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilterCategory == "UPLOADS",
                    onClick = { selectedFilterCategory = if (selectedFilterCategory == "UPLOADS") null else "UPLOADS" },
                    label = { Text("Uploads") }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilterCategory == "DOWNLOADS",
                    onClick = { selectedFilterCategory = if (selectedFilterCategory == "DOWNLOADS") null else "DOWNLOADS" },
                    label = { Text("Downloads") }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilterCategory == "DELETIONS",
                    onClick = { selectedFilterCategory = if (selectedFilterCategory == "DELETIONS") null else "DELETIONS" },
                    label = { Text("Deletions") }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilterCategory == "CONFLICTS",
                    onClick = { selectedFilterCategory = if (selectedFilterCategory == "CONFLICTS") null else "CONFLICTS" },
                    label = { Text("Conflicts") }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilterCategory == "ERRORS",
                    onClick = { selectedFilterCategory = if (selectedFilterCategory == "ERRORS") null else "ERRORS" },
                    label = { Text("Errors") }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredActivities.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Activities Found",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Synchronization actions and file transfers will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredActivities, key = { it.id }) { activity ->
                    ActivityItemCard(activity = activity)
                }
            }
        }
    }
}

@Composable
private fun ActivityItemCard(activity: SyncActivityEntity) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("activity_card_${activity.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActivityTypeBadge(type = activity.type)

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activity.path,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = activity.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = FormatUtils.formatTimestamp(activity.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (activity.fileSize > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = FormatUtils.formatBytes(activity.fileSize),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = NcPrimaryBlue
                    )
                }
            }
        }
    }
}
