package com.example.ui.screens

import android.os.Build
import android.os.Environment
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConflictStrategy
import com.example.data.model.SyncIntervalUnit
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel
) {
    val account by viewModel.account.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val serverStatus by viewModel.serverConnectionStatus.collectAsState()
    val isTestingConnection by viewModel.isTestingConnection.collectAsState()

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

    // Local Folder Configuration
    var customPathInput by remember(settings?.customLocalSyncPath) {
        mutableStateOf(settings?.customLocalSyncPath ?: "")
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
                                text = "Choose where synchronized Nextcloud files are stored on this device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Current Active Location Info Box
                    val effectivePath = viewModel.getEffectiveLocalSyncPath()
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
                                    fontSize = 12.sp
                                ),
                                color = NcPrimaryBlue
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Quick Presets:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick Preset Buttons
                    val defaultInternal = viewModel.getDefaultInternalPath()
                    val extDocs = viewModel.getExternalDocumentsPath()
                    val extDownloads = viewModel.getExternalDownloadPath()

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
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (customPathInput.isEmpty()) NcPrimaryBlue.copy(alpha = 0.12f) else Color.Transparent
                            ),
                            modifier = Modifier.weight(1f).testTag("preset_default_storage_btn")
                        ) {
                            Text("Default App Storage", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = {
                                customPathInput = extDocs
                                viewModel.updateCustomLocalSyncPath(extDocs)
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (customPathInput == extDocs) NcPrimaryBlue.copy(alpha = 0.12f) else Color.Transparent
                            ),
                            modifier = Modifier.weight(1f).testTag("preset_docs_storage_btn")
                        ) {
                            Text("Documents", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = {
                                customPathInput = extDownloads
                                viewModel.updateCustomLocalSyncPath(extDownloads)
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (customPathInput == extDownloads) NcPrimaryBlue.copy(alpha = 0.12f) else Color.Transparent
                            ),
                            modifier = Modifier.weight(1f).testTag("preset_downloads_storage_btn")
                        ) {
                            Text("Downloads", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = customPathInput,
                        onValueChange = { customPathInput = it },
                        label = { Text("Custom Absolute Directory Path") },
                        placeholder = { Text(defaultInternal) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        },
                        supportingText = {
                            Text("Leave blank to use default internal sandbox ($defaultInternal)")
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
                            Text("Reset Default")
                        }

                        Button(
                            onClick = {
                                viewModel.updateCustomLocalSyncPath(customPathInput.trim())
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
