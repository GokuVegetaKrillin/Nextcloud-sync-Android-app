package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ActivityType
import com.example.data.model.SyncStatus
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

object FormatUtils {
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    fun formatSpeed(speedKbps: Double): String {
        return if (speedKbps >= 1024.0) {
            String.format(Locale.US, "%.1f MB/s", speedKbps / 1024.0)
        } else {
            String.format(Locale.US, "%.0f KB/s", speedKbps)
        }
    }

    fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0) return "Never"
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        if (diff < 60_000) return "Just now"
        if (diff < 3600_000) return "${diff / 60_000}m ago"
        if (diff < 86400_000) return "${diff / 3600_000}h ago"
        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatScheduledTime(timestamp: Long): String {
        if (timestamp <= 0) return "Not scheduled"
        val now = System.currentTimeMillis()
        val diff = timestamp - now
        if (diff <= 0) return "Due now"
        val mins = diff / 60_000
        val hours = mins / 60
        val days = hours / 24
        return when {
            days > 0 -> "In $days day(s) (${hours % 24}h)"
            hours > 0 -> "In $hours hour(s) (${mins % 60}m)"
            mins > 0 -> "In $mins minute(s)"
            else -> "In ${(diff / 1000)}s"
        }
    }
}

@Composable
fun SyncStatusBadge(status: SyncStatus, modifier: Modifier = Modifier) {
    val (bg, fg, label) = when (status) {
        SyncStatus.IDLE -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "Up to date")
        SyncStatus.SYNCING -> Triple(NcPrimaryBlue.copy(alpha = 0.15f), NcPrimaryBlue, "Syncing...")
        SyncStatus.PAUSED -> Triple(NcWarningAmber.copy(alpha = 0.15f), NcWarningAmber, "Paused")
        SyncStatus.SUCCESS -> Triple(NcSuccessGreen.copy(alpha = 0.15f), NcSuccessGreen, "Synchronized")
        SyncStatus.CONFLICT -> Triple(NcWarningAmber.copy(alpha = 0.2f), NcWarningAmber, "Conflicts Pending")
        SyncStatus.ERROR -> Triple(NcErrorRed.copy(alpha = 0.15f), NcErrorRed, "Sync Error")
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(fg)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = fg
            )
        }
    }
}

@Composable
fun ActivityTypeBadge(type: ActivityType, modifier: Modifier = Modifier) {
    val (color, label) = when (type) {
        ActivityType.DOWNLOAD -> Pair(NcSyncBlue, "DOWNLOAD")
        ActivityType.UPLOAD -> Pair(NcSuccessGreen, "UPLOAD")
        ActivityType.DELETE_LOCAL -> Pair(NcErrorRed, "LOCAL DELETE")
        ActivityType.DELETE_REMOTE -> Pair(NcErrorRed, "REMOTE DELETE")
        ActivityType.CREATE_DIR_LOCAL -> Pair(NcCyanAccent, "NEW FOLDER")
        ActivityType.CREATE_DIR_REMOTE -> Pair(NcCyanAccent, "REMOTE FOLDER")
        ActivityType.CONFLICT_DETECTED -> Pair(NcWarningAmber, "CONFLICT")
        ActivityType.CONFLICT_RESOLVED -> Pair(NcSuccessGreen, "RESOLVED")
        ActivityType.ERROR -> Pair(NcErrorRed, "ERROR")
        ActivityType.INFO -> Pair(MaterialTheme.colorScheme.outline, "INFO")
    }

    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
