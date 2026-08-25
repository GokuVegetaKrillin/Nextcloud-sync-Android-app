package com.example.ui.screens

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.data.model.ConflictStrategy
import com.example.data.model.SyncIntervalUnit
import com.example.ui.MainViewModel
import com.example.ui.theme.*
import com.example.util.StoragePermissionHelper
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val account by viewModel.account.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val serverStatus by viewModel.serverConnectionStatus.collectAsState()
    val isTestingConnection by viewModel.isTestingConnection.collectAsState()
    val isStoragePermissionGranted by viewModel.storagePermissionGranted.collectAsState()

    // Observe app lifecycle so returning from Android System Settings instantly refreshes permissions & status
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStoragePermissionState()
                viewModel.refreshLocalFiles()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Local states for inputs
    var serverUrlInput by remember(account?.serverUrl) { mutableStateOf(account?.serverUrl ?: "https://cloud.example.com") }
    var usernameInput by remember(account?.username) { mutableStateOf(account?.username ?: "admin") }
    var passwordInput by remember(account?.passwordOrToken) { mutableStateOf(account?.passwordOrToken ?: "") }
    var displayNameInput by remember(account?.displayName) { mutableStateOf(account?.displayName ?: "Nextcloud User") }
    var trustAllCerts by remember(account?.trustAllCerts) { mutableStateOf(account?.trustAllCerts ?: true) }
    var isSimulatedDemo by remember(account?.isSimulatedDemo) { mutableStateOf(account?.isSimulatedDemo ?: true) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    // Interval inputs
    var intervalValueInput by remember(settings?.syncIntervalValue) {
        mutableStateOf((settings?.syncIntervalValue ?: 15).toString())
    }
    var selectedUnit by remember(settings?.syncIntervalUnit) {
        mutableStateOf(settings?.syncIntervalUnit ?: SyncIntervalUnit.MINUTES)
    }

    // Local Folder Configuration Input
    var customPathInput by remember(settings?.customLocalSyncPath) {
        mutableStateOf(settings?.customLocalSyncPath ?: "")
    }

    var showPermissionRequestDialog by remember { mutableStateOf(false) }

    // Effective active directory
    val effectivePath = remember(settings?.customLocalSyncPath) {
        val custom = settings?.customLocalSyncPath?.trim()
        if (!custom.isNullOrEmpty()) custom else viewModel.getDefaultInternalPath()
    }
    val isPathExternal = remember(effectivePath) {
        StoragePermissionHelper.isExternalPath(effectivePath, context)
    }

    // Permission Prompt Dialog
    if (showPermissionRequestDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionRequestDialog = false },
            icon = {
                Icon(Icons.Filled.FolderSpecial, contentDescription = null, tint = NcPrimaryBlue, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("Storage Access Permission Required", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            },
            text = {
                Column {
                    Text(
                        "Nextcloud Sync is set to store files in:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = effectivePath,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = NcPrimaryBlue,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "To create, sync, and make these files accessible to your Android file manager and apps without root, please allow 'All files access' in Android settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionRequestDialog = false
                        StoragePermissionHelper.openStoragePermissionSettings(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Grant Permission")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRequestDialog = false }) {
                    Text("Not Now")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp)
    ) {
        // Section 1: Android Local Storage & Destination Directory
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth().testTag("storage_location_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = NcPrimaryBlue)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Android Local Storage Location",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Choose where synchronized Nextcloud files are saved on this device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Current Active Location Info Box
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Storage,
                                    contentDescription = null,
                                    tint = NcPrimaryBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Current Active Folder:",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = effectivePath,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = NcPrimaryBlue
                            )
                        }
                    }

                    // Storage Permission Status Banner if using external path
                    if (isPathExternal) {
                        Spacer(modifier = Modifier.height(10.dp))
                        if (!isStoragePermissionGranted) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = NcWarningAmber.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, NcWarningAmber.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = NcWarningAmber,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Storage Permission Required",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = NcWarningAmber
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Nextcloud Sync needs 'All Files Access' to create and save files to shared storage ($effectivePath) so they are visible in your file manager without root.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            StoragePermissionHelper.openStoragePermissionSettings(context)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = NcWarningAmber),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.fillMaxWidth().testTag("grant_storage_perm_btn")
                                    ) {
                                        Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Grant Storage Access Permission", color = Color.Black, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = NcSuccessGreen.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(10.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = NcSuccessGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Storage permission active: Writable without root",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = NcSuccessGreen
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Quick Presets (Accessible without root):",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick Preset Buttons
                    val defaultInternal = viewModel.getDefaultInternalPath()
                    val sharedNextcloud = viewModel.getSharedStorageNextcloudPath()
                    val extDocs = viewModel.getExternalDocumentsPath()
                    val extDownloads = viewModel.getExternalDownloadPath()

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    customPathInput = sharedNextcloud
                                    viewModel.updateCustomLocalSyncPath(sharedNextcloud)
                                    if (!isStoragePermissionGranted) {
                                        showPermissionRequestDialog = true
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (effectivePath == sharedNextcloud) NcPrimaryBlue.copy(alpha = 0.15f) else Color.Transparent
                                ),
                                modifier = Modifier.weight(1f).testTag("preset_shared_storage_btn")
                            ) {
                                Icon(Icons.Outlined.FolderShared, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Shared Storage (/sdcard/Nextcloud)", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    customPathInput = extDocs
                                    viewModel.updateCustomLocalSyncPath(extDocs)
                                    if (!isStoragePermissionGranted) {
                                        showPermissionRequestDialog = true
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (effectivePath == extDocs) NcPrimaryBlue.copy(alpha = 0.15f) else Color.Transparent
                                ),
                                modifier = Modifier.weight(1f).testTag("preset_docs_storage_btn")
                            ) {
                                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Documents", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = {
                                    customPathInput = extDownloads
                                    viewModel.updateCustomLocalSyncPath(extDownloads)
                                    if (!isStoragePermissionGranted) {
                                        showPermissionRequestDialog = true
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (effectivePath == extDownloads) NcPrimaryBlue.copy(alpha = 0.15f) else Color.Transparent
                                ),
                                modifier = Modifier.weight(1f).testTag("preset_downloads_storage_btn")
                            ) {
                                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Downloads", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    customPathInput = ""
                                    viewModel.updateCustomLocalSyncPath("")
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (effectivePath == defaultInternal) NcPrimaryBlue.copy(alpha = 0.15f) else Color.Transparent
                                ),
                                modifier = Modifier.weight(1f).testTag("preset_default_storage_btn")
                            ) {
                                Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("App Internal Sandbox", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = customPathInput,
                        onValueChange = { customPathInput = it },
                        label = { Text("Custom Absolute Directory Path") },
                        placeholder = { Text(sharedNextcloud) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        },
                        supportingText = {
                            Text("e.g. $sharedNextcloud or $extDocs")
                        },
                        modifier = Modifier.fillMaxWidth().testTag("custom_sync_path_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                customPathInput = ""
                                viewModel.updateCustomLocalSyncPath("")
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).testTag("reset_storage_path_btn")
                        ) {
                            Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Sandbox")
                        }

                        Button(
                            onClick = {
                                val target = customPathInput.trim()
                                viewModel.updateCustomLocalSyncPath(target)
                                if (target.isNotEmpty() && StoragePermissionHelper.isExternalPath(target, context) && !isStoragePermissionGranted) {
                                    showPermissionRequestDialog = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).testTag("save_storage_path_btn")
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Location")
                        }
                    }
                }
            }
        }

        // Section 2: Server Configuration (Editable Server Address)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth().testTag("server_config_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Dns, contentDescription = null, tint = NcPrimaryBlue)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Nextcloud Server Address",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "You can update the server URL at any time",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("https://cloud.yourdomain.com") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Outlined.Language, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("server_url_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text("Username") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Outlined.Person, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("username_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("App Password or Token") },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = {
                            Icon(Icons.Outlined.Lock, contentDescription = null)
                        },
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = "Toggle password visibility"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("password_input")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { trustAllCerts = !trustAllCerts }
                    ) {
                        Checkbox(
                            checked = trustAllCerts,
                            onCheckedChange = { trustAllCerts = it },
                            colors = CheckboxDefaults.colors(checkedColor = NcPrimaryBlue)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("Trust Self-Signed SSL Certificates", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                            Text("Allow connecting to private Nextcloud home servers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { isSimulatedDemo = !isSimulatedDemo }
                    ) {
                        Checkbox(
                            checked = isSimulatedDemo,
                            onCheckedChange = { isSimulatedDemo = it },
                            colors = CheckboxDefaults.colors(checkedColor = NcCyanAccent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("Demo / Offline Simulation Mode", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                            Text("Test full 2-way sync without an external public server", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Save and test button
                    Button(
                        onClick = {
                            viewModel.updateAccountCredentials(
                                serverUrl = serverUrlInput.trim(),
                                username = usernameInput.trim(),
                                passwordOrToken = passwordInput.trim(),
                                displayName = displayNameInput.trim(),
                                trustAll = trustAllCerts,
                                isDemo = isSimulatedDemo
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().testTag("save_server_btn")
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing Connection...")
                        } else {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Server & Test Connection")
                        }
                    }

                    serverStatus?.let { status ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (status.isConnected) NcSuccessGreen.copy(alpha = 0.12f) else NcErrorRed.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (status.isConnected) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                    contentDescription = null,
                                    tint = if (status.isConnected) NcSuccessGreen else NcErrorRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (status.isConnected) "Connected: ${status.version ?: "Nextcloud Server"} (${status.responseTimeMs}ms)"
                                    else "Connection failed: ${status.errorMessage ?: "Server unreachable"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (status.isConnected) NcSuccessGreen else NcErrorRed
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section 3: Custom Sync Interval (Arbitrary Minutes / Hours / Days)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth().testTag("sync_interval_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Timelapse, contentDescription = null, tint = NcPrimaryBlue)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Custom Sync Interval",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Set synchronization frequency to any minutes, hours, or days",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = intervalValueInput,
                            onValueChange = {
                                if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                    intervalValueInput = it
                                }
                            },
                            label = { Text("Interval") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("interval_value_input")
                        )

                        Column(modifier = Modifier.weight(2f)) {
                            Text("Unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                SyncIntervalUnit.entries.forEach { unit ->
                                    val isSelected = selectedUnit == unit
                                    OutlinedButton(
                                        onClick = { selectedUnit = unit },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (isSelected) NcPrimaryBlue else Color.Transparent,
                                            contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1f).testTag("unit_btn_${unit.name}")
                                    ) {
                                        Text(
                                            text = unit.name.lowercase().replaceFirstChar { it.uppercase() },
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val parsedVal = intervalValueInput.toIntOrNull() ?: 15
                    Text(
                        text = "Current schedule: Background sync triggers every $parsedVal ${selectedUnit.name.lowercase()}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val valInt = intervalValueInput.toIntOrNull() ?: 15
                            viewModel.setSyncInterval(valInt.coerceAtLeast(1), selectedUnit)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("apply_interval_btn")
                    ) {
                        Text("Apply Sync Interval")
                    }
                }
            }
        }

        // Section 4: Sync Behavior (Sync New Folders By Default, Background Sync)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth().testTag("sync_behavior_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Sync Behavior & Preferences",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Sync New Folders By Default
                    val syncNew = settings?.syncNewFoldersByDefault ?: true
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sync New Server Folders by Default", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(
                                "Automatically select and synchronize new folders created on Nextcloud",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = syncNew,
                            onCheckedChange = { viewModel.setSyncNewFoldersByDefault(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = NcPrimaryBlue),
                            modifier = Modifier.testTag("settings_sync_new_folders_switch")
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Background Sync Switch
                    val runBg = settings?.runInBackground ?: true
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Synchronization", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(
                                "Allow sync to operate in the background using Android alarms and foreground service",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = runBg,
                            onCheckedChange = { viewModel.updateRunInBackground(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = NcPrimaryBlue),
                            modifier = Modifier.testTag("background_sync_switch")
                        )
                    }
                }
            }
        }

        // Section 5: Maintenance & Journal Reset
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth().testTag("maintenance_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Diagnostics & Maintenance",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Troubleshooting tools for database journal and sync reconciliation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = { viewModel.resetJournal() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NcWarningAmber),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("reset_journal_btn")
                    ) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reset Sync Journal & Re-index")
                    }
                }
            }
        }
    }
}
