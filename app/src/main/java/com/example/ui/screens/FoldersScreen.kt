package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.SyncFolderConfigEntity
import com.example.ui.MainViewModel
import com.example.ui.components.FormatUtils
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    viewModel: MainViewModel
) {
    val folders by viewModel.folders.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val syncNewByDefault = settings?.syncNewFoldersByDefault ?: true

    var showAddFolderDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddFolderDialog = true },
                containerColor = NcPrimaryBlue,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("add_folder_fab")
            ) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Add folder")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
        ) {
            // 1. Key Setting Card: "Sync new remote folders by default"
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("sync_new_folders_setting_card")
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
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(NcPrimaryBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudSync,
                                    contentDescription = "Sync new folders",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = "Sync New Nextcloud Folders",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (syncNewByDefault) {
                                        "New folders added to the server are synchronized automatically."
                                    } else {
                                        "New folders on the server are excluded by default until manually selected."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Switch(
                            checked = syncNewByDefault,
                            onCheckedChange = { viewModel.setSyncNewFoldersByDefault(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = NcPrimaryBlue
                            ),
                            modifier = Modifier.testTag("sync_new_folders_toggle")
                        )
                    }
                }
            }

            // 2. Header & Batch Actions
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Selective Folder Sync",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Select folders to sync to this mobile device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.refreshRemoteFolders() },
                            modifier = Modifier.testTag("refresh_folders_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Scan server folders",
                                tint = NcPrimaryBlue
                            )
                        }

                        TextButton(
                            onClick = {
                                folders.forEach { folder ->
                                    if (!folder.isSelected) {
                                        viewModel.toggleFolderSelection(folder.remotePath, true)
                                    }
                                }
                            },
                            modifier = Modifier.testTag("select_all_folders_btn")
                        ) {
                            Text("All", fontWeight = FontWeight.SemiBold)
                        }

                        TextButton(
                            onClick = {
                                folders.forEach { folder ->
                                    if (folder.isSelected) {
                                        viewModel.toggleFolderSelection(folder.remotePath, false)
                                    }
                                }
                            },
                            modifier = Modifier.testTag("deselect_all_folders_btn")
                        ) {
                            Text("None", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // 3. Folders List
            if (folders.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = "No folders",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No Sync Folders Configured",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap 'Sync Now' on the dashboard to discover folders from your Nextcloud server, or tap the button below to add one.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            } else {
                items(folders, key = { it.remotePath }) { folder ->
                    FolderSyncItemCard(
                        folder = folder,
                        onToggleSelection = { isSelected ->
                            viewModel.toggleFolderSelection(folder.remotePath, isSelected)
                        }
                    )
                }
            }

            // 4. Desktop Client Selective Sync Info
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "Info",
                                tint = NcPrimaryBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Nextcloud Desktop Sync Parity",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Just like the desktop client, unselected folders will remain on your Nextcloud cloud server without taking up local storage space on your device. Any changes made to selected folders will sync bidirectionally.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showAddFolderDialog) {
        AddFolderDialog(
            onDismiss = { showAddFolderDialog = false },
            onAdd = { name, isSelected ->
                viewModel.createRemoteDemoFolder(name)
                viewModel.createLocalFolder(name)
                viewModel.toggleFolderSelection("/$name", isSelected)
                showAddFolderDialog = false
            }
        )
    }
}

@Composable
private fun FolderSyncItemCard(
    folder: SyncFolderConfigEntity,
    onToggleSelection: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (folder.isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (folder.isSelected) 1.dp else 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelection(!folder.isSelected) }
            .testTag("folder_item_${folder.displayName}")
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
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (folder.isSelected) NcPrimaryBlue.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (folder.isSelected) Icons.Filled.Folder else Icons.Outlined.FolderOff,
                        contentDescription = folder.displayName,
                        tint = if (folder.isSelected) NcPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = folder.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (folder.isSelected) FontWeight.Bold else FontWeight.Normal
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Path: ${folder.remotePath} • ${if (folder.isSelected) "Synchronized" else "Excluded"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Checkbox(
                checked = folder.isSelected,
                onCheckedChange = { onToggleSelection(it) },
                colors = CheckboxDefaults.colors(
                    checkedColor = NcPrimaryBlue
                ),
                modifier = Modifier.testTag("checkbox_${folder.displayName}")
            )
        }
    }
}

@Composable
private fun AddFolderDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, isSelected: Boolean) -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    var syncImmediately by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Add Sync Folder", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the name of the folder on Nextcloud to synchronize:",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name") },
                    placeholder = { Text("e.g. Work, Music, Archives") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("add_folder_input")
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { syncImmediately = !syncImmediately }
                ) {
                    Checkbox(
                        checked = syncImmediately,
                        onCheckedChange = { syncImmediately = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Synchronize immediately", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (folderName.isNotBlank()) {
                        onAdd(folderName.trim(), syncImmediately)
                    }
                },
                enabled = folderName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                modifier = Modifier.testTag("confirm_add_folder_btn")
            ) {
                Text("Add Folder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
