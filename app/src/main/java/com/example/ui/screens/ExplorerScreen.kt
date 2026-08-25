package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.ui.MainViewModel
import com.example.ui.components.FormatUtils
import com.example.ui.theme.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    viewModel: MainViewModel,
    onSyncNow: () -> Unit
) {
    val currentSubpath by viewModel.currentLocalSubpath.collectAsState()
    val localFiles by viewModel.localFiles.collectAsState()

    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showSimulateRemoteDialog by remember { mutableStateOf(false) }
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var fileContentText by remember { mutableStateOf("") }
    var isEditingFile by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(
                    onClick = { showSimulateRemoteDialog = true },
                    containerColor = NcCyanAccent,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("simulate_remote_fab")
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = "Simulate remote change")
                }

                FloatingActionButton(
                    onClick = { showCreateFileDialog = true },
                    containerColor = NcPrimaryBlue,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("create_local_file_fab")
                ) {
                    Icon(Icons.Filled.NoteAdd, contentDescription = "Create local file")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Navigation Breadcrumb Bar & Quick Sync
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth().testTag("breadcrumb_bar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (currentSubpath.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    val parent = currentSubpath.substringBeforeLast('/', "")
                                    viewModel.navigateToSubpath(parent)
                                },
                                modifier = Modifier.size(32.dp).testTag("explorer_back_btn")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        Icon(
                            imageVector = Icons.Filled.FolderSpecial,
                            contentDescription = "Synced Root",
                            tint = NcPrimaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (currentSubpath.isEmpty()) "Nextcloud (Local)" else "Nextcloud$currentSubpath",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row {
                        IconButton(
                            onClick = { showCreateFolderDialog = true },
                            modifier = Modifier.size(36.dp).testTag("new_folder_btn")
                        ) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder", modifier = Modifier.size(20.dp))
                        }

                        IconButton(
                            onClick = {
                                onSyncNow()
                            },
                            modifier = Modifier.size(36.dp).testTag("sync_explorer_btn")
                        ) {
                            Icon(Icons.Filled.Sync, contentDescription = "Sync", tint = NcPrimaryBlue, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // File items
            if (localFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = "Empty folder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "This folder is empty",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap 'Sync Now' on the dashboard or create a file to test two-way sync!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showCreateFileDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Test File")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(localFiles, key = { it.absolutePath }) { file ->
                        FileItemRow(
                            file = file,
                            onClick = {
                                if (file.isDirectory) {
                                    val newSub = if (currentSubpath.isEmpty()) "/${file.name}" else "$currentSubpath/${file.name}"
                                    viewModel.navigateToSubpath(newSub)
                                } else {
                                    viewingFile = file
                                    fileContentText = try { file.readText() } catch (e: Exception) { "Binary file or cannot read text." }
                                    isEditingFile = false
                                }
                            },
                            onDelete = {
                                viewModel.deleteLocalFile(file)
                            }
                        )
                    }
                }
            }
        }
    }

    // View/Edit File Modal
    viewingFile?.let { file ->
        AlertDialog(
            onDismissRequest = { viewingFile = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { isEditingFile = !isEditingFile }) {
                        Icon(
                            imageVector = if (isEditingFile) Icons.Filled.Visibility else Icons.Filled.Edit,
                            contentDescription = "Toggle edit"
                        )
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Size: ${FormatUtils.formatBytes(file.length())} • Modified: ${FormatUtils.formatTimestamp(file.lastModified())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isEditingFile) {
                        OutlinedTextField(
                            value = fileContentText,
                            onValueChange = { fileContentText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 240.dp)
                                .testTag("file_content_input"),
                            placeholder = { Text("Edit content...") }
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 240.dp)
                        ) {
                            Text(
                                text = fileContentText,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (isEditingFile) {
                    Button(
                        onClick = {
                            file.writeText(fileContentText)
                            viewModel.refreshLocalFiles()
                            viewingFile = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                        modifier = Modifier.testTag("save_file_btn")
                    ) {
                        Text("Save & Sync")
                    }
                } else {
                    Button(onClick = { viewingFile = null }) {
                        Text("Close")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewingFile = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Create File Dialog
    if (showCreateFileDialog) {
        var fileName by remember { mutableStateOf("") }
        var fileContent by remember { mutableStateOf("Testing Nextcloud desktop bidirectional sync on Android.") }

        AlertDialog(
            onDismissRequest = { showCreateFileDialog = false },
            title = { Text("Create Local File", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("File Name") },
                        placeholder = { Text("e.g. MyNotes.txt, document.md") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_file_name_input")
                    )
                    OutlinedTextField(
                        value = fileContent,
                        onValueChange = { fileContent = it },
                        label = { Text("Content") },
                        modifier = Modifier.fillMaxWidth().height(100.dp).testTag("new_file_content_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (fileName.isNotBlank()) {
                            val cleanName = if (fileName.contains('.')) fileName.trim() else "${fileName.trim()}.txt"
                            viewModel.createLocalFile(cleanName, fileContent)
                            showCreateFileDialog = false
                        }
                    },
                    enabled = fileName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                    modifier = Modifier.testTag("confirm_create_file_btn")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Create Folder Dialog
    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Local Folder", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name") },
                    placeholder = { Text("e.g. Invoices, Reports") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_folder_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            viewModel.createLocalFolder(folderName.trim())
                            showCreateFolderDialog = false
                        }
                    },
                    enabled = folderName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                    modifier = Modifier.testTag("confirm_create_folder_btn")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Simulate Remote Change Dialog (for testing 2-way sync)
    if (showSimulateRemoteDialog) {
        var targetFolder by remember { mutableStateOf("Documents") }
        var remoteFileName by remember { mutableStateOf("Server_Update.txt") }
        var remoteContent by remember { mutableStateOf("This file was added directly on the Nextcloud server to verify downloading.") }

        AlertDialog(
            onDismissRequest = { showSimulateRemoteDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = NcCyanAccent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Simulate Server File", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Add or modify a file on the simulated Nextcloud server to test download synchronization:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = targetFolder,
                        onValueChange = { targetFolder = it },
                        label = { Text("Server Folder") },
                        placeholder = { Text("Documents, Photos, Notes") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remoteFileName,
                        onValueChange = { remoteFileName = it },
                        label = { Text("Remote File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remoteContent,
                        onValueChange = { remoteContent = it },
                        label = { Text("Remote Content") },
                        modifier = Modifier.fillMaxWidth().height(90.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (remoteFileName.isNotBlank()) {
                            viewModel.createRemoteDemoFile(targetFolder, remoteFileName, remoteContent)
                            showSimulateRemoteDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NcCyanAccent),
                    modifier = Modifier.testTag("confirm_simulate_remote_btn")
                ) {
                    Text("Add to Server")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSimulateRemoteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FileItemRow(
    file: File,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("file_row_${file.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                val icon = when {
                    file.isDirectory -> Icons.Filled.Folder
                    file.name.endsWith(".txt") || file.name.endsWith(".md") -> Icons.Outlined.Description
                    file.name.endsWith(".pdf") -> Icons.Outlined.PictureAsPdf
                    file.name.endsWith(".jpg") || file.name.endsWith(".png") -> Icons.Outlined.Image
                    file.name.contains("(conflicted copy") -> Icons.Filled.Warning
                    else -> Icons.Outlined.InsertDriveFile
                }

                val tint = when {
                    file.name.contains("(conflicted copy") -> NcWarningAmber
                    file.isDirectory -> NcPrimaryBlue
                    else -> MaterialTheme.colorScheme.primary
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (file.isDirectory) "Folder" else "${FormatUtils.formatBytes(file.length())} • ${FormatUtils.formatTimestamp(file.lastModified())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp).testTag("delete_file_${file.name}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
